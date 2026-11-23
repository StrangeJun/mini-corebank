package com.corebank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// JWT 만 사용하므로 기본 in-memory 사용자(랜덤 비밀번호) 자동 구성은 끈다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class CorebankApplication {

    public static void main(String[] args) {
        SpringApplication.run(CorebankApplication.class, args);
    }
}
