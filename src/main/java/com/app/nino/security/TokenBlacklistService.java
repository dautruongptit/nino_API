package com.app.nino.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Danh sach den cac phien/token JWT da bi thu hoi (logout, dang xuat tu xa) —
 * luu trong Redis voi TTL bang dung thoi gian con lai toi khi token tu het
 * han, de Redis tu don dep, khong can job xoa thu cong.
 *
 * Khoa ghi vao day co the la 1 trong 2 loai, dung CHUNG mot khong gian khoa
 * Redis va deu tu het han theo TTL:
 *   - sid (session id) — voi token duoc cap kem claim "sid": chi can 1 lan
 *     ghi la chan duoc ca access lan refresh token cua phien do, ke ca cac
 *     token moi sinh ra qua /auth/refresh sau nay (chung giu nguyen sid).
 *   - chinh chuoi token — voi token CU mint truoc khi co claim "sid": khong
 *     co sid de chan theo phien nen phai chan theo dung chuoi token, giong co
 *     che logout truoc khi trien khai sid.
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

    /** @param key sid cua phien, hoac chinh chuoi token voi token cu khong co claim "sid". */
    public void blacklist(String key, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) return;
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + key, "1", ttl);
        } catch (Exception e) {
            log.error("[TokenBlacklist] Khong the ghi Redis, token se van hop le toi khi tu het han: {}",
                e.getMessage());
        }
    }

    /** @param key sid cua phien, hoac chinh chuoi token voi token cu khong co claim "sid". */
    public boolean isBlacklisted(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + key));
        } catch (Exception e) {
            log.warn("[TokenBlacklist] Loi kiem tra Redis, bo qua blacklist check: {}", e.getMessage());
            return false;
        }
    }
}
