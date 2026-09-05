package com.app.nino.service;

import com.app.nino.model.entity.RequestLog;
import com.app.nino.repository.RequestLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestLogServiceTest {

    @Mock private RequestLogRepository requestLogRepository;

    @InjectMocks private RequestLogService requestLogService;

    @Test
    void shouldSkip_tra_ve_true_cho_cac_path_ha_tang() {
        assertTrue(requestLogService.shouldSkip("/actuator/health"));
        assertTrue(requestLogService.shouldSkip("/internal/health-check"));
        assertTrue(requestLogService.shouldSkip("/swagger-ui/index.html"));
        assertTrue(requestLogService.shouldSkip("/v3/api-docs/swagger-config"));
    }

    @Test
    void shouldSkip_tra_ve_false_cho_api_binh_thuong() {
        assertFalse(requestLogService.shouldSkip("/notifications/5/read"));
        assertFalse(requestLogService.shouldSkip("/auth/login"));
        assertFalse(requestLogService.shouldSkip(null));
    }

    @Test
    void maskAndTruncate_mask_field_nhay_cam() {
        String body = "{\"email\":\"a@b.com\",\"password\":\"secret123\"}";

        String result = requestLogService.maskAndTruncate(body);

        assertTrue(result.contains("\"password\":\"***\""));
        assertTrue(result.contains("\"email\":\"a@b.com\""));
    }

    @Test
    void maskAndTruncate_mask_token_trong_response_login() {
        String body = "{\"accessToken\":\"eyJabc\",\"refreshToken\":\"eyJdef\"}";

        String result = requestLogService.maskAndTruncate(body);

        assertTrue(result.contains("\"accessToken\":\"***\""));
        assertTrue(result.contains("\"refreshToken\":\"***\""));
    }

    @Test
    void maskAndTruncate_cat_bot_khi_qua_dai() {
        String longBody = "x".repeat(RequestLogService.MAX_BODY_LENGTH + 500);

        String result = requestLogService.maskAndTruncate(longBody);

        assertTrue(result.endsWith("...(truncated)"));
        assertTrue(result.length() < longBody.length());
    }

    @Test
    void maskAndTruncate_tra_ve_null_neu_body_null() {
        assertNull(requestLogService.maskAndTruncate(null));
    }

    @Test
    void save_khong_nem_exception_ra_ngoai_khi_repository_loi() {
        RequestLog log = RequestLog.builder().httpMethod("GET").uri("/x").build();
        when(requestLogRepository.save(any())).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> requestLogService.save(log));
        verify(requestLogRepository).save(log);
    }
}
