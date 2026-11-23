package com.corebank.auth.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "corebank.jwt")
public record JwtProperties(String secret, Duration accessTokenValidity) {
}
