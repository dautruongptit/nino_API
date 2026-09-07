package com.app.nino.security;

import com.app.nino.model.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtTokenProviderTest {

    @Mock private TokenBlacklistService tokenBlacklistService;

    private JwtTokenProvider provider;

    private static final long ACCESS_MS  = 3600_000L;   // 1h
    private static final long REFRESH_MS = 604_800_000L; // 7 ngay

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret",
            "test-secret-key-must-be-at-least-32-bytes-long-for-hs256");
        ReflectionTestUtils.setField(provider, "jwtExpiration", ACCESS_MS);
        ReflectionTestUtils.setField(provider, "refreshExpiration", REFRESH_MS);
        ReflectionTestUtils.setField(provider, "tokenBlacklistService", tokenBlacklistService);
    }

    @Test
    void validateToken_returnsFalse_whenBlacklisted() {
        String token = provider.generateAccessToken(1L, Set.of(Role.builder().name("ROLE_USER").build()));
        when(tokenBlacklistService.isBlacklisted(token)).thenReturn(true);

        assertFalse(provider.validateToken(token));
    }

    @Test
    void validateToken_returnsTrue_whenNotBlacklisted() {
        String token = provider.generateAccessToken(1L, Set.of(Role.builder().name("ROLE_USER").build()));
        when(tokenBlacklistService.isBlacklisted(token)).thenReturn(false);

        assertTrue(provider.validateToken(token));
    }

    @Test
    void getRemainingValidity_returnsApproximatelyConfiguredExpiration() {
        String token = provider.generateAccessToken(1L, Set.of(Role.builder().name("ROLE_USER").build()));

        Duration remaining = provider.getRemainingValidity(token);

        assertTrue(remaining.toMillis() > ACCESS_MS - 5000);
        assertTrue(remaining.toMillis() <= ACCESS_MS);
    }

    @Test
    void refreshToken_hasConfiguredSevenDayWindow() {
        // Khoa lai hanh vi "phien khong dung 7 ngay se tu het han": moi lan
        // refresh phat hanh refresh token MOI voi han 7 ngay tinh tu LUC DO
        // (sliding window) — nen mien la con dung app it nhat 1 lan/7 ngay,
        // refresh token khong bao gio thuc su het han; ngung dung qua 7 ngay
        // thi refresh token cu se het han that.
        String refreshToken = provider.generateRefreshToken(1L);

        assertEquals("refresh", provider.getTokenType(refreshToken));
        Duration remaining = provider.getRemainingValidity(refreshToken);
        assertTrue(remaining.toMillis() > REFRESH_MS - 5000);
        assertTrue(remaining.toMillis() <= REFRESH_MS);
    }
}
