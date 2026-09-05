package com.app.nino.filter;

import com.app.nino.model.entity.RequestLog;
import com.app.nino.service.RequestLogService;
import com.app.nino.util.DeviceParser;
import com.app.nino.util.SensitiveDataMasker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final String MDC_REQUEST_ID = "requestId";
    private static final int MAX_BODY_LOG_LENGTH = 2000;

    private final RequestLogService requestLogService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_REQUEST_ID, requestId);

        // Bo qua wrap body cho multipart (upload avatar) — tranh doc het stream
        // truoc khi Spring MultipartResolver kip xu ly.
        boolean isMultipart = request.getContentType() != null
            && request.getContentType().toLowerCase().startsWith("multipart/");

        ContentCachingRequestWrapper  wrappedRequest  = isMultipart
            ? null : new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();
        try {
            log.info("[Request] --> {} {} ip={}",
                request.getMethod(), buildUri(request), DeviceParser.getClientIp(request));

            filterChain.doFilter(wrappedRequest != null ? wrappedRequest : request, wrappedResponse);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            String uri = buildUri(request);

            String requestBody = wrappedRequest != null
                ? getContentAsString(wrappedRequest.getContentAsByteArray()) : null;
            String responseBody = getContentAsString(wrappedResponse.getContentAsByteArray());

            if (StringUtils.hasText(requestBody) && log.isDebugEnabled()) {
                log.debug("[Request] body {} {}: {}",
                    request.getMethod(), uri, SensitiveDataMasker.mask(truncateForLog(requestBody)));
            }

            log.info("[Request] <-- {} {} status={} duration={}ms",
                request.getMethod(), uri, wrappedResponse.getStatus(), duration);

            if (!requestLogService.shouldSkip(request.getRequestURI())) {
                saveAuditLog(request, wrappedResponse, requestId, uri, requestBody, responseBody, duration);
            }

            wrappedResponse.copyBodyToResponse();
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private void saveAuditLog(HttpServletRequest request, ContentCachingResponseWrapper wrappedResponse,
                               String requestId, String uri, String requestBody, String responseBody,
                               long duration) {
        Object authUserId = request.getAttribute("authUserId");

        RequestLog requestLog = RequestLog.builder()
            .requestId(requestId)
            .httpMethod(request.getMethod())
            .uri(uri)
            .userId(authUserId instanceof Long ? (Long) authUserId : null)
            .requestBody(requestLogService.maskAndTruncate(requestBody))
            .responseBody(requestLogService.maskAndTruncate(responseBody))
            .statusCode(wrappedResponse.getStatus())
            .ipAddress(DeviceParser.getClientIp(request))
            .durationMs(duration)
            .createdAt(LocalDateTime.now())
            .build();

        requestLogService.save(requestLog);
    }

    private String buildUri(HttpServletRequest request) {
        String query = request.getQueryString();
        return query != null ? request.getRequestURI() + "?" + query : request.getRequestURI();
    }

    private String getContentAsString(byte[] content) {
        if (content == null || content.length == 0) return "";
        return new String(content, StandardCharsets.UTF_8);
    }

    private String truncateForLog(String body) {
        return body.length() > MAX_BODY_LOG_LENGTH
            ? body.substring(0, MAX_BODY_LOG_LENGTH) + "...(truncated)" : body;
    }
}
