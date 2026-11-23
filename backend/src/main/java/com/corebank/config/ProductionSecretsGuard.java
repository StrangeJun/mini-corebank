package com.corebank.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * prod 프로필에서 데모용 기본 시크릿으로 기동하는 것을 막는다 (RV-M1-01, RV-M1-03).
 * default / test 프로필은 원클릭 데모를 위해 기본값을 허용한다.
 *
 * <p>아래 상수는 application.yml 의 기본값과 같아야 한다 (ProductionSecretsGuardTest 가 동기화를 검증).
 */
@Component
public class ProductionSecretsGuard {

    static final String DEFAULT_JWT_SECRET = "EbWOaMuPtQEWQt3NhvkxI5k4r54pGxGKkLTx6AYpxAksOj5YbbSiyIBkxcb+kVNq";
    static final String DEFAULT_ADMIN_PASSWORD = "Admin1234!";

    private static final String JWT_SECRET_KEY = "corebank.jwt.secret";
    private static final String ADMIN_PASSWORD_KEY = "corebank.seed.admin-password";

    private final Environment environment;

    public ProductionSecretsGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void verify() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        rejectDefault(JWT_SECRET_KEY, DEFAULT_JWT_SECRET, "JWT_SECRET");
        rejectDefault(ADMIN_PASSWORD_KEY, DEFAULT_ADMIN_PASSWORD, "ADMIN_INITIAL_PASSWORD");
    }

    private void rejectDefault(String key, String defaultValue, String envName) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank() || value.equals(defaultValue)) {
            // 값 자체는 메시지에 넣지 않는다.
            throw new IllegalStateException("prod 프로필에서는 " + key + " 기본값을 사용할 수 없습니다. "
                    + envName + " 환경변수로 안전한 값을 주입하세요.");
        }
    }
}
