package com.corebank.transaction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.corebank.auth.jwt.AuthUser;
import com.corebank.auth.jwt.JwtTokenProvider;
import com.corebank.domain.user.User;
import com.corebank.domain.user.UserRepository;
import com.corebank.domain.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 트랜잭션 테스트 공통. 실제 커밋·락 동작을 보기 위해 테스트 트랜잭션을 쓰지 않고 JDBC 로 정리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class TransactionTestSupport {

    static final String PIN = "1234";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUpLedger() {
        jdbc.update("delete from ledger_entries");
        jdbc.update("delete from scheduled_transfers");
        jdbc.update("delete from transactions");
        jdbc.update("delete from idempotency_records");
        jdbc.update("delete from audit_logs");
        jdbc.update("delete from accounts where is_system = false");
        jdbc.update("delete from users where email like '%@tx.test'");
        jdbc.update("update accounts set balance = 0 where account_number = 'SYSTEM-VAULT'");
    }

    record Customer(Long userId, String token) {
    }

    Customer newCustomer(String name) {
        User user = userRepository.save(new User(name + "-" + UUID.randomUUID() + "@tx.test", "x", name, UserRole.USER));
        return new Customer(user.getId(),
                tokenProvider.createAccessToken(new AuthUser(user.getId(), user.getEmail(), user.getRole())));
    }

    JsonNode openAccount(Customer c, String extraJson) throws Exception {
        String body = "{\"pin\":\"" + PIN + "\"" + (extraJson.isEmpty() ? "" : "," + extraJson) + "}";
        String res = mockMvc.perform(post("/api/accounts").header("Authorization", "Bearer " + c.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(res);
    }

    ResultActions call(String path, Customer c, String key, String json) throws Exception {
        var req = post("/api/transactions/" + path).header("Authorization", "Bearer " + c.token())
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mockMvc.perform(req);
    }

    ResultActions deposit(Customer c, long accountId, String amount) throws Exception {
        return call("deposit", c, UUID.randomUUID().toString(),
                "{\"accountId\":" + accountId + ",\"amount\":" + amount + ",\"description\":\"입금\"}");
    }

    ResultActions withdraw(Customer c, long accountId, String amount) throws Exception {
        return call("withdraw", c, UUID.randomUUID().toString(),
                "{\"accountId\":" + accountId + ",\"amount\":" + amount + ",\"pin\":\"" + PIN + "\"}");
    }

    ResultActions transfer(Customer c, long fromId, String toNumber, String amount) throws Exception {
        return call("transfer", c, UUID.randomUUID().toString(),
                "{\"fromAccountId\":" + fromId + ",\"toAccountNumber\":\"" + toNumber + "\",\"amount\":" + amount
                        + ",\"pin\":\"" + PIN + "\"}");
    }

    BigDecimal balance(long accountId) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, accountId);
    }

    BigDecimal vaultBalance() {
        return jdbc.queryForObject("select balance from accounts where account_number = 'SYSTEM-VAULT'",
                BigDecimal.class);
    }

    long vaultId() {
        return jdbc.queryForObject("select id from accounts where account_number = 'SYSTEM-VAULT'", Long.class);
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
