package com.corebank.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 로그·감사 로그·응답용 민감정보 마스킹. */
public final class MaskingUtil {

    private static final char MASK = '*';
    private static final int VISIBLE_SUFFIX = 4;
    private static final int DEFAULT_VISIBLE_PREFIX = 3;

    /** password / pin 계열 키의 값: key=value, key: value, "key":"value" */
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)(\"?\\b[A-Za-z_]*(?:password|passwd|pwd|pin)[A-Za-z_]*\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^\\s,;&}\\]]+)");

    /** 하이픈 계좌번호(110-123-456789) 또는 10~16자리 연속 숫자. */
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile(
            "(?<![\\d-])(?:\\d{2,6}-\\d{2,6}-\\d{2,8}|\\d{10,16})(?![\\d-])");

    private MaskingUtil() {
    }

    /**
     * 첫 구간(하이픈이 없으면 앞 3자리)과 뒤 4자리 숫자만 남긴다.
     * 예: 110-123-456789 → 110-***-**6789. 숫자가 없는 값(SYSTEM-VAULT)은 그대로 둔다.
     */
    public static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isEmpty()) {
            return accountNumber;
        }
        int totalDigits = 0;
        for (char c : accountNumber.toCharArray()) {
            if (Character.isDigit(c)) {
                totalDigits++;
            }
        }
        if (totalDigits == 0) {
            return accountNumber;
        }

        int hyphen = accountNumber.indexOf('-');
        int prefix = hyphen > 0 ? countDigits(accountNumber.substring(0, hyphen)) : DEFAULT_VISIBLE_PREFIX;
        // 짧은 번호는 앞자리를 보여주지 않고 뒤 4자리만 남긴다.
        if (totalDigits <= prefix + VISIBLE_SUFFIX) {
            prefix = 0;
        }
        int suffixStart = Math.max(totalDigits - VISIBLE_SUFFIX, prefix);

        StringBuilder sb = new StringBuilder(accountNumber.length());
        int digitIndex = 0;
        for (char c : accountNumber.toCharArray()) {
            if (Character.isDigit(c)) {
                boolean visible = digitIndex < prefix || (digitIndex >= suffixStart && totalDigits > VISIBLE_SUFFIX);
                sb.append(visible ? c : MASK);
                digitIndex++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 예: hong@example.com → ho***@example.com */
    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return visible + "***" + email.substring(at);
    }

    /** 자유 문자열 안의 password/pin 값과 계좌번호를 마스킹한다. */
    public static String maskSensitiveText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = SECRET_PAIR.matcher(text).replaceAll(m -> {
            String value = m.group(2);
            String replacement = value.startsWith("\"") ? "\"****\"" : "****";
            return Matcher.quoteReplacement(m.group(1) + replacement);
        });
        return ACCOUNT_NUMBER.matcher(masked)
                .replaceAll(m -> Matcher.quoteReplacement(maskAccountNumber(m.group())));
    }

    private static int countDigits(String s) {
        int n = 0;
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                n++;
            }
        }
        return n;
    }
}
