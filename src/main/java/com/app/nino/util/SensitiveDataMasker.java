package com.app.nino.util;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * Mask cac field nhay cam (password/token...) trong 1 chuoi JSON truoc khi
 * ghi ra log file hoac luu DB (RequestLoggingFilter, RequestLogService).
 */
public final class SensitiveDataMasker {

    private static final Pattern SENSITIVE_FIELD_PATTERN = Pattern.compile(
        "(?i)\"(password|newPassword|oldPassword|token|accessToken|refreshToken|idToken|secret)\"\\s*:\\s*\"[^\"]*\"");

    private SensitiveDataMasker() {}

    public static String mask(String body) {
        if (!StringUtils.hasText(body)) return body;
        return SENSITIVE_FIELD_PATTERN.matcher(body).replaceAll("\"$1\":\"***\"");
    }
}
