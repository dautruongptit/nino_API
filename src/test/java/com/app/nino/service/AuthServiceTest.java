package com.app.nino.service;

import com.app.nino.model.entity.User;
import com.app.nino.repository.LoginHistoryRepository;
import com.app.nino.repository.RoleRepository;
import com.app.nino.repository.UserDeviceRepository;
import com.app.nino.repository.UserRepository;
import com.app.nino.security.JwtTokenProvider;
import com.app.nino.security.TokenBlacklistService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepo;
    @Mock private RoleRepository roleRepo;
    @Mock private LoginHistoryRepository loginHistoryRepo;
    @Mock private UserDeviceRepository userDeviceRepo;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private TokenBlacklistService tokenBlacklistService;

    @InjectMocks
    private AuthService service;

    // ── LOGOUT ───────────────────────────────────────────────────────────

    @Test
    void logout_blacklistsAccessToken() {
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));

        service.logout(1L, "access-1", null, null);

        verify(tokenBlacklistService).blacklist("access-1", Duration.ofMinutes(30));
    }

    @Test
    void logout_withRefreshToken_alsoBlacklistsRefreshToken() {
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));
        when(jwtTokenProvider.validateToken("refresh-1")).thenReturn(true);
        when(jwtTokenProvider.getRemainingValidity("refresh-1")).thenReturn(Duration.ofDays(7));

        service.logout(1L, "access-1", "refresh-1", null);

        verify(tokenBlacklistService).blacklist("refresh-1", Duration.ofDays(7));
    }

    @Test
    void logout_withInvalidRefreshToken_doesNotBlacklistIt() {
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));
        when(jwtTokenProvider.validateToken("bad-refresh")).thenReturn(false);

        service.logout(1L, "access-1", "bad-refresh", null);

        verify(tokenBlacklistService, never()).blacklist(eq("bad-refresh"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void logout_withFcmToken_deletesThatDeviceForCallingUser() {
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));

        service.logout(1L, "access-1", null, "fcm-tok-1");

        verify(userDeviceRepo).deleteByFcmTokenAndUserId("fcm-tok-1", 1L);
    }

    @Test
    void logout_withoutFcmToken_doesNotTouchDevices() {
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));

        service.logout(1L, "access-1", null, null);

        verifyNoInteractions(userDeviceRepo);
    }

    // ── LOGIN — device name capture ─────────────────────────────────────────

    @Test
    void login_withDeviceName_savesItOnLoginHistory() {
        User user = User.builder().id(1L).email("a@b.com")
            .passwordHash("hashed").status("ACT").roles(new java.util.HashSet<>()).build();
        when(userRepo.findByEmail("a@b.com")).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("pw", "hashed")).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(any(), any())).thenReturn("access-1");
        when(jwtTokenProvider.generateRefreshToken(any())).thenReturn("refresh-1");

        jakarta.servlet.http.HttpServletRequest httpRequest =
            org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        when(httpRequest.getHeader("User-Agent")).thenReturn("okhttp/4.12");
        // DeviceParser.getClientIp() doc header nay truoc — stub de tranh
        // Mockito strict-stubbing bao "argument mismatch" tren cung method getHeader().
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);

        com.app.nino.model.dto.request.LoginRequest req = new com.app.nino.model.dto.request.LoginRequest();
        req.setEmail("a@b.com");
        req.setPassword("pw");
        req.setDeviceName("Pixel 8");

        service.login(req, httpRequest);

        org.mockito.ArgumentCaptor<com.app.nino.model.entity.LoginHistory> captor =
            org.mockito.ArgumentCaptor.forClass(com.app.nino.model.entity.LoginHistory.class);
        verify(loginHistoryRepo).save(captor.capture());
        assertEquals("Pixel 8", captor.getValue().getDeviceName());
    }
}
