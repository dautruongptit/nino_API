package com.app.nino.filter;

import com.app.nino.model.dto.response.BaseResponse;
import com.app.nino.util.DeviceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Gioi han so request/cua so thoi gian theo IP cho cac endpoint xac thuc
 * cong khai (/auth/**) — bo sung cho AuthService.MAX_FAILED_ATTEMPTS (chi
 * khoa theo TUNG TAI KHOAN) de chan them:
 *   - credential-stuffing dan trai nhieu tai khoan tu 1 IP/botnet nho
 *   - spam tao tai khoan rac qua /auth/register
 *   - co y lam ke khoa tai khoan nguoi khac (goi sai mat khau lien tuc)
 *     it nhat cung bi cham lai dang ke thay vi khong gioi han
 *
 * Thuat toan fixed-window bang Redis INCR + EXPIRE — don gian, dung chung
 * ket noi Redis da co san (giong TokenBlacklistService), khong can them
 * thu vien rate-limit rieng cho quy mo hien tai.
 *
 * Dat @Order truoc ca RequestLoggingFilter (HIGHEST_PRECEDENCE) mot chut
 * de van duoc ghi log/audit binh thuong khi bi chan, nhung van chay TRUOC
 * toan bo Spring Security filter chain (JwtAuthFilter...) de tu choi som,
 * do request roi khong ton chi phi xac thuc/DB.
 *
 * Fail OPEN neu Redis loi/khong ket noi duoc — giong TokenBlacklistService,
 * uu tien khong lam sap dang nhap vi ly do ha tang.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "ratelimit:auth:";

    /** Tap dong, huu han cac action hop le — bat ky gia tri nao khac deu gom
     * chung vao "other". Xem giai thich chi tiet o resolveAction(). */
    private static final Set<String> KNOWN_ACTIONS = Set.of("login", "register", "refresh", "google", "logout");

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limit.window-seconds:60}")
    private long windowSeconds;

    @Value("${app.rate-limit.login-max:10}")
    private int loginMax;

    @Value("${app.rate-limit.register-max:5}")
    private int registerMax;

    @Value("${app.rate-limit.default-max:30}")
    private int defaultMax;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String authPrefix = request.getContextPath() + "/auth/";
        String uri = request.getRequestURI();

        if (!enabled || !uri.startsWith(authPrefix)) {
            filterChain.doFilter(request, response);
            return;
        }

        String action = resolveAction(uri, authPrefix);
        int limit = resolveLimit(action);
        String ip = DeviceParser.getClientIp(request);
        String bucketKey = KEY_PREFIX + action + ":" + ip;

        if (isOverLimit(bucketKey, limit)) {
            log.warn("[RateLimit] Vuot gioi han: action={} ip={} limit={}/{}s", action, ip, limit, windowSeconds);
            writeTooManyRequests(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Rut gon URI ve 1 trong so HUU HAN action da biet ("other" cho moi
     * truong hop con lai) — KHONG duoc dung thang phan URI con lai sau
     * "/auth/" lam key (nhu ban dau), vi Spring dispatcher bo qua matrix
     * param (";x=1") va nhieu bien the path khac khi routing, nghia la
     * "/auth/login;x=1", "/auth/login;x=2"... deu goi TRUNG 1 handler
     * login nhung se tao ra vo so Redis key KHAC NHAU neu dung raw suffix —
     * xoa sach tac dung rate-limit (attacker chi can doi hau to moi request
     * la vuot gioi han vo han lan) VA lam Redis phinh to vo to (moi key
     * mot TTL rieng, khong bao gio hoi tu).
     *
     * Chi lay chuoi chu/so/gach ngang/gach duoi lien tuc dau tien (ten
     * action thuc su, vd "login" trong "login;x=1" hay "login/"), roi
     * khop voi tap KNOWN_ACTIONS co san — dam bao so luong bucket Redis
     * bi chan boi |KNOWN_ACTIONS ∪ {other}| × so IP, khong phu thuoc noi
     * dung URI attacker tu chon.
     */
    private String resolveAction(String uri, String authPrefix) {
        String suffix = uri.substring(authPrefix.length());
        int end = 0;
        while (end < suffix.length()) {
            char c = suffix.charAt(end);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') break;
            end++;
        }
        String candidate = suffix.substring(0, end).toLowerCase(Locale.ROOT);
        return KNOWN_ACTIONS.contains(candidate) ? candidate : "other";
    }

    private int resolveLimit(String action) {
        return switch (action) {
            case "login" -> loginMax;
            case "register" -> registerMax;
            default -> defaultMax;
        };
    }

    /** true neu da vuot [limit] request trong cua so [windowSeconds] hien tai. */
    private boolean isOverLimit(String key, int limit) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // Chi dat TTL lan dau tao key — tranh moi request lam troi han cua so (sliding).
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
            }
            return count != null && count > limit;
        } catch (Exception e) {
            log.warn("[RateLimit] Loi Redis, bo qua kiem tra rate-limit (fail open): {}", e.getMessage());
            return false;
        }
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429); // HttpServletResponse khong co hang so 429 san (SC_TOO_MANY_REQUESTS)
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
            BaseResponse.error("Ban thao tac qua nhanh, vui long thu lai sau it phut", "TOO_MANY_REQUESTS")));
    }
}
