package com.corebank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.auth.jwt.JwtTokenProvider;
import com.corebank.domain.account.Account;
import com.corebank.domain.account.AccountRepository;
import com.corebank.domain.audit.AuditLog;
import com.corebank.domain.audit.AuditLogRepository;
import com.corebank.domain.user.User;
import com.corebank.domain.user.UserRepository;
import com.corebank.domain.user.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired jakarta.persistence.EntityManager entityManager;

    String ownerToken;
    String otherToken;
    String adminToken;

    @BeforeEach
    void setUp() {
        ownerToken = tokenFor(userRepository.save(new User("owner@test.com", "x", "소유자", UserRole.USER)));
        otherToken = tokenFor(userRepository.save(new User("other@test.com", "x", "타인", UserRole.USER)));
        adminToken = tokenFor(userRepository.findByEmail("admin@corebank.com").orElseThrow());
    }

    @Test
    void createAccountHashesPinAndUsesDefaults() throws Exception {
        long id = createAccount(ownerToken, "{\"pin\":\"1234\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber").value(org.hamcrest.Matchers.matchesPattern("110-\\d{3}-\\d{6}")))
                .andExpect(jsonPath("$.maskedAccountNumber").value(org.hamcrest.Matchers.matchesPattern("110-\\*{3}-\\*{2}\\d{4}")))
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.perTxLimit").value(10000000))
                .andExpect(jsonPath("$.dailyLimit").value(50000000))
                .andExpect(jsonPath("$.pin").doesNotExist())
                .andExpect(jsonPath("$.pinHash").doesNotExist())
                .andReturn().getResponse().getContentAsString().transform(this::id);

        Account account = accountRepository.findById(id).orElseThrow();
        assertThat(account.getPinHash()).isNotEqualTo("1234");
        assertThat(passwordEncoder.matches("1234", account.getPinHash())).isTrue();
        assertThat(account.isSystem()).isFalse();
        assertThat(account.getPinFailCount()).isZero();

        AuditLog log = auditLogRepository.findAll().stream()
                .filter(l -> l.getActionType().equals("ACCOUNT_CREATE")).findFirst().orElseThrow();
        assertThat(log.getMaskedSummary()).doesNotContain(account.getAccountNumber(), "1234");
    }

    @Test
    void createAccountWithCustomLimits() throws Exception {
        createAccount(ownerToken, "{\"pin\":\"123456\",\"perTxLimit\":1000000,\"dailyLimit\":3000000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.perTxLimit").value(1000000))
                .andExpect(jsonPath("$.dailyLimit").value(3000000));
    }

    @Test
    void createAccountRejectsInvalidPinAndLimits() throws Exception {
        for (String body : new String[] {
                "{\"pin\":\"123\"}", "{\"pin\":\"1234567\"}", "{\"pin\":\"12ab\"}", "{}",
                "{\"pin\":\"1234\",\"perTxLimit\":0}", "{\"pin\":\"1234\",\"dailyLimit\":-1}",
                "{\"pin\":\"1234\",\"perTxLimit\":1000.5}"}) {
            createAccount(ownerToken, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Test
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"1234\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listReturnsOnlyMyAccountsInCreationOrder() throws Exception {
        long first = id(createAccount(ownerToken, "{\"pin\":\"1234\"}").andReturn().getResponse().getContentAsString());
        long second = id(createAccount(ownerToken, "{\"pin\":\"1234\"}").andReturn().getResponse().getContentAsString());
        createAccount(otherToken, "{\"pin\":\"1234\"}");

        mockMvc.perform(get("/api/accounts").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(jsonPath("$[0].pinHash").doesNotExist());
    }

    @Test
    void detailAllowsOwnerAndAdminButForbidsOthers() throws Exception {
        long id = id(createAccount(ownerToken, "{\"pin\":\"1234\"}").andReturn().getResponse().getContentAsString());

        getAccount(ownerToken, id).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        getAccount(adminToken, id).andExpect(status().isOk());
        getAccount(otherToken, id)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCOUNT_ACCESS"));
        getAccount(ownerToken, 999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void closeZeroBalanceAccount() throws Exception {
        long id = id(createAccount(ownerToken, "{\"pin\":\"1234\"}").andReturn().getResponse().getContentAsString());

        closeAccount(ownerToken, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(auditLogRepository.findAll()).extracting(AuditLog::getActionType).contains("ACCOUNT_CLOSE");

        closeAccount(ownerToken, id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
    }

    @Test
    void closeRejectsOthersNonZeroBalanceAndLocked() throws Exception {
        long id = id(createAccount(ownerToken, "{\"pin\":\"1234\"}").andReturn().getResponse().getContentAsString());

        closeAccount(otherToken, id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACCOUNT_ACCESS"));
        closeAccount(adminToken, id).andExpect(status().isForbidden());

        // 테스트 트랜잭션의 1차 캐시를 비워 직접 수정한 값이 보이게 한다.
        jdbcTemplate.update("update accounts set balance = 1000 where id = ?", id);
        entityManager.clear();
        closeAccount(ownerToken, id).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("잔액이 남아있는 계좌는 해지할 수 없습니다"));

        jdbcTemplate.update("update accounts set balance = 0, status = 'LOCKED' where id = ?", id);
        entityManager.clear();
        closeAccount(ownerToken, id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void systemVaultIsNotAccessibleToUsers() throws Exception {
        long vaultId = accountRepository.findByAccountNumber("SYSTEM-VAULT").orElseThrow().getId();
        getAccount(ownerToken, vaultId).andExpect(status().isForbidden());
        closeAccount(ownerToken, vaultId).andExpect(status().isForbidden());
    }

    private String tokenFor(User user) {
        return tokenProvider.createAccessToken(new AuthUser(user.getId(), user.getEmail(), user.getRole()));
    }

    private ResultActions createAccount(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/accounts").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions getAccount(String token, long id) throws Exception {
        return mockMvc.perform(get("/api/accounts/" + id).header("Authorization", "Bearer " + token));
    }

    private ResultActions closeAccount(String token, long id) throws Exception {
        return mockMvc.perform(patch("/api/accounts/" + id + "/close").header("Authorization", "Bearer " + token));
    }

    private long id(String json) {
        try {
            return objectMapper.readTree(json).get("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
