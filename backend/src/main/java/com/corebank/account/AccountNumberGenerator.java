package com.corebank.account;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/** 110-XXX-XXXXXX 형식 계좌번호 후보 생성. 중복 확인은 호출 측에서 한다. */
@Component
public class AccountNumberGenerator {

    private static final String BANK_PREFIX = "110";

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        return String.format("%s-%03d-%06d", BANK_PREFIX, random.nextInt(1_000), random.nextInt(1_000_000));
    }
}
