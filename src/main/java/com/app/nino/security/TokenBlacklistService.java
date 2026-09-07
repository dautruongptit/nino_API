package com.app.nino.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Danh sach den JWT da bi thu hoi (logout) — luu trong Redis voi TTL bang
 * dung thoi gian con lai toi khi token tu het han, de Redis tu don dep,
 * khong can job xoa thu cong.
 *
 * Redis loi/khong ket noi duoc -> fail OPEN (khong chan request vi ly do
 * ha tang) — giong cach RedisConfig.CacheErrorHandler xu ly loi cache o
 * noi khac trong app nay.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "auth:blacklist:";

    private final RedisTemplate<String, Object> redisTemplate;

    public void blacklist(String token, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) return;
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + token, "1", ttl);
        } catch (Exception e) {
            log.warn("[TokenBlacklist] Khong the ghi Redis, token se van hop le toi khi tu het han: {}",
                e.getMessage());
        }
    }

    public boolean isBlacklisted(String token) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + token));
        } catch (Exception e) {
            log.warn("[TokenBlacklist] Loi kiem tra Redis, bo qua blacklist check: {}", e.getMessage());
            return false;
        }
    }
}
