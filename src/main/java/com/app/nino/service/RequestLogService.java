package com.app.nino.service;

import com.app.nino.model.entity.RequestLog;
import com.app.nino.repository.RequestLogRepository;
import com.app.nino.util.SensitiveDataMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Luu audit log cua HTTP request/response (dang JSON, kem nguoi tao request)
 * vao DB. Chay bat dong bo (@Async) de khong lam cham response tra ve client;
 * loi khi luu (neu co) chi log warning, khong duoc phep lam fail request goc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestLogService {

    /** DB dung TEXT nen chua duoc nhieu, nhung van gioi han de tranh phinh bang
     * boi vai response list qua lon (VD GET /notifications). */
    static final int MAX_BODY_LENGTH = 4000;

    /** Cac path ha tang/health-check bi goi lien tuc, khong phai hanh dong
     * cua nguoi dung — van ghi file log nhu cu nhung khong luu DB. */
    private static final List<String> EXCLUDED_PREFIXES = List.of(
        "/actuator/health", "/internal/health-check",
        "/swagger-ui", "/v3/api-docs", "/api-docs");

    private final RequestLogRepository requestLogRepository;

    public boolean shouldSkip(String uri) {
        if (uri == null) return false;
        return EXCLUDED_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    public String maskAndTruncate(String body) {
        String masked = SensitiveDataMasker.mask(body);
        if (masked == null) return null;
        return masked.length() > MAX_BODY_LENGTH
            ? masked.substring(0, MAX_BODY_LENGTH) + "...(truncated)" : masked;
    }

    @Async("requestLogExecutor")
    public void save(RequestLog requestLog) {
        try {
            requestLogRepository.save(requestLog);
        } catch (Exception e) {
            log.warn("[RequestLogService] Khong the luu request log uri={}: {}",
                requestLog.getUri(), e.getMessage());
        }
    }
}
