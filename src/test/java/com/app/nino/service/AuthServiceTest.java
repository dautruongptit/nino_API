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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void logout_blacklistsSessionBySid() {
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));

        service.logout(1L, "access-1", null, null);

        verify(tokenBlacklistService).blacklist("sid-1", Duration.ofMinutes(30));
    }

    @Test
    void logout_withRefreshToken_usesRefreshTokenRemainingValidityAsTtl() {
        // Refresh token song lau hon access token — dung han cua no lam TTL
        // blacklist de chan ca 2 (cung sid) den tan luc refresh token het han.
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.validateToken("refresh-1")).thenReturn(true);
        when(jwtTokenProvider.getRemainingValidity("refresh-1")).thenReturn(Duration.ofDays(7));

        service.logout(1L, "access-1", "refresh-1", null);

        verify(tokenBlacklistService).blacklist("sid-1", Duration.ofDays(7));
    }

    @Test
    void logout_withInvalidRefreshToken_fallsBackToAccessTokenTtl() {
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));
        when(jwtTokenProvider.validateToken("bad-refresh")).thenReturn(false);

        service.logout(1L, "access-1", "bad-refresh", null);

        verify(tokenBlacklistService).blacklist("sid-1", Duration.ofMinutes(30));
    }

    @Test
    void logout_marksMatchingLoginHistoryRowRevoked() {
        com.app.nino.model.entity.LoginHistory history = com.app.nino.model.entity.LoginHistory.builder()
            .id(9L).sessionId("sid-1").build();
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));
        when(loginHistoryRepo.findBySessionId("sid-1")).thenReturn(java.util.Optional.of(history));

        service.logout(1L, "access-1", null, null);

        assertNotNull(history.getRevokedAt());
        verify(loginHistoryRepo).save(history);
    }

    @Test
    void logout_withFcmToken_deletesThatDeviceForCallingUser() {
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));

        service.logout(1L, "access-1", null, "fcm-tok-1");

        verify(userDeviceRepo).deleteByFcmTokenAndUserId("fcm-tok-1", 1L);
    }

    @Test
    void logout_withoutFcmToken_doesNotTouchDevices() {
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));

        service.logout(1L, "access-1", null, null);

        verifyNoInteractions(userDeviceRepo);
    }

    // ── UPDATE PROFILE — phone/gender/ngay sinh ─────────────────────────────

    @Test
    void updateProfile_setsPhoneGenderAndBirthDate() {
        User user = User.builder().id(1L).email("a@b.com").fullName("Cu ten").build();
        when(userRepo.findById(1L)).thenReturn(java.util.Optional.of(user));
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.app.nino.model.dto.request.UpdateProfileRequest req =
            new com.app.nino.model.dto.request.UpdateProfileRequest();
        req.setFullName("Ten moi");
        req.setPhone("0912345678");
        req.setGender("FEMALE");
        req.setBirthMonth(5);
        req.setBirthDay(20);
        req.setBirthYear(1995);

        var result = service.updateProfile(1L, req);

        assertEquals("0912345678", result.getPhone());
        assertEquals("FEMALE", result.getGender());
        assertEquals(5, result.getBirthMonth());
        assertEquals(20, result.getBirthDay());
        assertEquals(1995, result.getBirthYear());
    }

    @Test
    void updateProfile_withNullGenderAndBirthDate_clearsThem() {
        User user = User.builder().id(1L).email("a@b.com").fullName("Cu ten")
            .gender(User.Gender.MALE).birthMonth(1).birthDay(2).birthYear(1990).build();
        when(userRepo.findById(1L)).thenReturn(java.util.Optional.of(user));
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.app.nino.model.dto.request.UpdateProfileRequest req =
            new com.app.nino.model.dto.request.UpdateProfileRequest();
        req.setFullName("Ten moi");

        var result = service.updateProfile(1L, req);

        assertEquals(null, result.getGender());
        assertEquals(null, result.getBirthMonth());
    }

    // ── LOGIN — device name capture ─────────────────────────────────────────

    @Test
    void login_withDeviceName_savesItOnLoginHistory() {
        User user = User.builder().id(1L).email("a@b.com")
            .passwordHash("hashed").status("ACT").roles(new java.util.HashSet<>()).build();
        when(userRepo.findByEmail("a@b.com")).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("pw", "hashed")).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(any(), any(), any())).thenReturn("access-1");
        when(jwtTokenProvider.generateRefreshToken(any(), any())).thenReturn("refresh-1");
        when(jwtTokenProvider.getRefreshExpirationMs()).thenReturn(604_800_000L);

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
        assertNotNull(captor.getValue().getSessionId());
        assertNotNull(captor.getValue().getRefreshExpiresAt());
    }
}
