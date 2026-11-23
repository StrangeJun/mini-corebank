package com.corebank.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.auth.jwt.JwtTokenProvider;
import com.corebank.domain.audit.AuditLog;
import com.corebank.domain.audit.AuditLogRepository;
import com.corebank.domain.audit.AuditResult;
import com.corebank.domain.user.User;
import com.corebank.domain.user.UserRepository;
import com.corebank.domain.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired JwtTokenProvider tokenProvider;

    @AfterEach
    void cleanUp() {
        auditLogRepository.deleteAll();
        userRepository.findByEmail("hong@example.com").ifPresent(userRepository::delete);
    }

    @Test
    void signupStoresBcryptHashAndAuditsMaskedEmail() throws Exception {
        signup("Hong@Example.com", "password123")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("hong@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());

        User user = userRepository.findByEmail("hong@example.com").orElseThrow();
        assertThat(user.getPasswordHash()).startsWith("$2").doesNotContain("password123");
        assertThat(auditLogRepository.findAll())
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getActionType()).isEqualTo("USER_SIGNUP");
                    assertThat(log.getMaskedSummary()).contains("ho***@example.com").doesNotContain("hong@example.com");
                });
    }

    @Test
    void duplicateEmailIs409() throws Exception {
        signup("hong@example.com", "password123").andExpect(status().isCreated());
        signup("HONG@example.com", "password456")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void signupValidatesInput() throws Exception {
        signup("not-an-email", "password123").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        signup("hong@example.com", "short").andExpect(status().isBadRequest());
    }

    @Test
    void loginSuccessReturnsTokenUsableForMe() throws Exception {
        signup("hong@example.com", "password123").andExpect(status().isCreated());

        String body = login("hong@example.com", "password123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("hong@example.com"))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("accessToken").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("hong@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));
        assertThat(auditLogRepository.findAll()).extracting(AuditLog::getActionType).contains("LOGIN_SUCCESS");
    }

    @Test
    void loginFailureIs401AndAuditedForWrongPasswordAndUnknownEmail() throws Exception {
        signup("hong@example.com", "password123").andExpect(status().isCreated());

        login("hong@example.com", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        login("nobody@example.com", "password123").andExpect(status().isUnauthorized());

        assertThat(auditLogRepository.findAll())
                .filteredOn(log -> log.getActionType().equals("LOGIN_FAIL"))
                .hasSize(2)
                .allSatisfy(log -> {
                    assertThat(log.getResult()).isEqualTo(AuditResult.FAILURE);
                    assertThat(log.getMaskedSummary()).doesNotContain("wrong-password", "password123");
                });
    }

    @Test
    void meWithoutOrInvalidTokenIs401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = tokenProvider.createAccessToken(new AuthUser(1L, "a@b.com", UserRole.USER));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminPathRequiresAdminRole() throws Exception {
        String userToken = tokenProvider.createAccessToken(new AuthUser(999L, "u@b.com", UserRole.USER));
        mockMvc.perform(get("/api/admin/anything").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 시드된 관리자로 로그인하면 ADMIN 토큰이 발급되고 인가를 통과한다 (매핑이 없어 404).
        String body = login("admin@corebank.com", "Admin1234!").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andReturn().getResponse().getContentAsString();
        String adminToken = objectMapper.readTree(body).get("accessToken").asText();
        int status = mockMvc.perform(get("/api/admin/anything").header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void corsPreflightAllowsFrontendOrigin() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Idempotency-Key, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions signup(String email, String password) throws Exception {
        String json = objectMapper.writeValueAsString(
                java.util.Map.of("email", email, "password", password, "name", "홍길동"));
        return mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        String json = objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", password));
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
