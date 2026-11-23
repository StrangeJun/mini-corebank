package com.corebank.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingUtilTest {

    @Test
    void masksAccountNumberKeepingFirstGroupAndLast4() {
        assertThat(MaskingUtil.maskAccountNumber("110-123-456789")).isEqualTo("110-***-**6789");
        assertThat(MaskingUtil.maskAccountNumber("1101234567890")).isEqualTo("110******7890");
    }

    @Test
    void keepsSystemVaultAndNull() {
        assertThat(MaskingUtil.maskAccountNumber("SYSTEM-VAULT")).isEqualTo("SYSTEM-VAULT");
        assertThat(MaskingUtil.maskAccountNumber(null)).isNull();
    }

    @Test
    void masksEmail() {
        assertThat(MaskingUtil.maskEmail("hong@example.com")).isEqualTo("ho***@example.com");
        assertThat(MaskingUtil.maskEmail("h@example.com")).isEqualTo("h***@example.com");
        assertThat(MaskingUtil.maskEmail("invalid")).isEqualTo("***");
    }

    @Test
    void masksSecretsAndAccountNumbersInText() {
        String text = "login password=Secret123! pin: 1234 {\"newPassword\":\"abc\"} from 110-123-456789";

        String masked = MaskingUtil.maskSensitiveText(text);

        assertThat(masked)
                .doesNotContain("Secret123!", "1234", "abc", "110-123-456789")
                .contains("password=****", "pin: ****", "\"newPassword\":\"****\"", "110-***-**6789");
    }

    @Test
    void leavesPlainTextUntouched() {
        assertThat(MaskingUtil.maskSensitiveText("DEPOSIT amount=50000 to SYSTEM-VAULT"))
                .isEqualTo("DEPOSIT amount=50000 to SYSTEM-VAULT");
    }
}
