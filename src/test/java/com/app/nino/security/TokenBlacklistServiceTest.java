package com.app.nino.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenBlacklistServiceTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOps;

    @InjectMocks
    private TokenBlacklistService service;

    @Test
    void blacklist_storesTokenWithGivenTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.blacklist("tok-1", Duration.ofMinutes(5));

        verify(valueOps).set(eq("auth:blacklist:tok-1"), eq("1"), eq(Duration.ofMinutes(5)));
    }

    @Test
    void blacklist_withZeroOrNegativeTtl_doesNotCallRedis() {
        // Token da/sap tu het han tu nhien -> khong can ton Redis luu lam gi.
        service.blacklist("tok-1", Duration.ZERO);
        service.blacklist("tok-2", Duration.ofSeconds(-5));

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void isBlacklisted_returnsTrue_whenKeyExists() {
        when(redisTemplate.hasKey("auth:blacklist:tok-1")).thenReturn(true);

        assertTrue(service.isBlacklisted("tok-1"));
    }

    @Test
    void isBlacklisted_returnsFalse_whenKeyMissing() {
        when(redisTemplate.hasKey("auth:blacklist:tok-1")).thenReturn(false);

        assertFalse(service.isBlacklisted("tok-1"));
    }

    @Test
    void isBlacklisted_failsOpen_whenRedisThrows() {
        // Redis down -> khong duoc chan dang nhap/request vi ly do ha tang;
        // token van duoc kiem tra chu ky + het han binh thuong o noi goi.
        when(redisTemplate.hasKey(any())).thenThrow(new RuntimeException("Redis down"));

        assertFalse(service.isBlacklisted("tok-1"));
    }
}
