package com.corebank.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class ProductionSecretsGuardTest {

    private static final String STRONG_SECRET = "c3Ryb25nLXByb2R1Y3Rpb24tc2VjcmV0LWZvci1jb3JlYmFuay0yMDI2LWtleQ==";

    @Test
    void prodWithDefaultJwtSecretFailsStartup() {
        MockEnvironment env = prod(ProductionSecretsGuard.DEFAULT_JWT_SECRET, "Str0ng!AdminPass");
        assertThatThrownBy(() -> new ProductionSecretsGuard(env).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corebank.jwt.secret");
    }

    @Test
    void prodWithBlankJwtSecretFailsStartup() {
        assertThatThrownBy(() -> new ProductionSecretsGuard(prod(" ", "Str0ng!AdminPass")).verify())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodWithDefaultAdminPasswordFailsStartup() {
        MockEnvironment env = prod(STRONG_SECRET, ProductionSecretsGuard.DEFAULT_ADMIN_PASSWORD);
        assertThatThrownBy(() -> new ProductionSecretsGuard(env).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corebank.seed.admin-password");
    }

    @Test
    void prodWithInjectedSecretsStarts() {
        assertThatCode(() -> new ProductionSecretsGuard(prod(STRONG_SECRET, "Str0ng!AdminPass")).verify())
                .doesNotThrowAnyException();
    }

    @Test
    void nonProdProfilesKeepDemoDefaults() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("corebank.jwt.secret", ProductionSecretsGuard.DEFAULT_JWT_SECRET)
                .withProperty("corebank.seed.admin-password", ProductionSecretsGuard.DEFAULT_ADMIN_PASSWORD);
        assertThatCode(() -> new ProductionSecretsGuard(env).verify()).doesNotThrowAnyException();
    }

    /** 가드의 기본값 상수가 application.yml 기본값과 어긋나면 가드가 무력화되므로 동기화를 검증한다. */
    @Test
    void guardDefaultsMatchApplicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();
        assertThat(props.getProperty("corebank.jwt.secret"))
                .isEqualTo("${JWT_SECRET:" + ProductionSecretsGuard.DEFAULT_JWT_SECRET + "}");
        assertThat(props.getProperty("corebank.seed.admin-password"))
                .isEqualTo("${ADMIN_INITIAL_PASSWORD:" + ProductionSecretsGuard.DEFAULT_ADMIN_PASSWORD + "}");
    }

    private static MockEnvironment prod(String secret, String adminPassword) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("corebank.jwt.secret", secret)
                .withProperty("corebank.seed.admin-password", adminPassword);
        env.setActiveProfiles("prod");
        return env;
    }
}
