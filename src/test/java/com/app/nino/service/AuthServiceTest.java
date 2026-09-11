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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        // Khong co dong LoginHistory (findBySessionId -> empty theo mac dinh) va
        // client khong gui refresh token -> TTL la tron cua so refresh, KHONG
        // phai han con lai cua access token.
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.getRefreshExpirationMs()).thenReturn(604_800_000L);

        service.logout(1L, "access-1", null, null);

        verify(tokenBlacklistService).blacklist("sid-1", Duration.ofMillis(604_800_000L));
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
    void logout_withInvalidRefreshToken_fallsBackToFullRefreshWindowTtl() {
        // Refresh token khong hop le = coi nhu khong gui; khong co dong lich su
        // nao -> phai chan tron cua so refresh, khong duoc thu nho ve han access
        // token (neu khong, refresh token that se song lai sau <=24h).
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.validateToken("bad-refresh")).thenReturn(false);
        when(jwtTokenProvider.getRefreshExpirationMs()).thenReturn(604_800_000L);

        service.logout(1L, "access-1", "bad-refresh", null);

        verify(tokenBlacklistService).blacklist("sid-1", Duration.ofMillis(604_800_000L));
    }

    @Test
    void logout_registeredSessionWithNoHistoryRow_blacklistsSidForFullRefreshWindow() {
        // register() KHONG tao dong LoginHistory, va client logout chi gui access
        // token. Truoc khi sua, TTL lay theo access token (<=24h) nen sau chung
        // do thoi gian refresh token (toi 7 ngay) lai dung duoc: /auth/refresh
        // khong can xac thuc nen bat ky ai giu token do cung hoi sinh duoc phien
        // da dang xuat. TTL bay gio phai phu tron cua so refresh.
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.getRefreshExpirationMs()).thenReturn(604_800_000L);
        when(loginHistoryRepo.findBySessionId("sid-1")).thenReturn(java.util.Optional.empty());

        service.logout(1L, "access-1", null, null);

        org.mockito.ArgumentCaptor<Duration> ttlCaptor = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(tokenBlacklistService).blacklist(eq("sid-1"), ttlCaptor.capture());
        assertEquals(Duration.ofDays(7), ttlCaptor.getValue());
        verify(jwtTokenProvider, never()).getRemainingValidity("access-1");
    }

    @Test
    void logout_withHistoryRow_usesItsTrackedRefreshExpiryAsTtl() {
        // Dong lich su la nguon chinh xac nhat (da gom ca cua so truot cua
        // /auth/refresh) — uu tien hon ca han access token lan mac dinh 7 ngay.
        com.app.nino.model.entity.LoginHistory history = com.app.nino.model.entity.LoginHistory.builder()
            .id(9L).sessionId("sid-1")
            .refreshExpiresAt(java.time.LocalDateTime.now().plusDays(5)).build();
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(loginHistoryRepo.findBySessionId("sid-1")).thenReturn(java.util.Optional.of(history));

        service.logout(1L, "access-1", null, null);

        org.mockito.ArgumentCaptor<Duration> ttlCaptor = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(tokenBlacklistService).blacklist(eq("sid-1"), ttlCaptor.capture());
        Duration ttl = ttlCaptor.getValue();
        assertTrue(ttl.toHours() >= 119 && ttl.toHours() <= 120, "TTL ~5 ngay, thuc te: " + ttl);
        verify(jwtTokenProvider, never()).getRefreshExpirationMs();
        verify(jwtTokenProvider, never()).getRemainingValidity("access-1");
    }

    @Test
    void logout_marksMatchingLoginHistoryRowRevoked() {
        com.app.nino.model.entity.LoginHistory history = com.app.nino.model.entity.LoginHistory.builder()
            .id(9L).sessionId("sid-1")
            .refreshExpiresAt(java.time.LocalDateTime.now().plusDays(3)).build();
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(loginHistoryRepo.findBySessionId("sid-1")).thenReturn(java.util.Optional.of(history));

        service.logout(1L, "access-1", null, null);

        assertNotNull(history.getRevokedAt());
        verify(loginHistoryRepo).save(history);
    }

    @Test
    void logout_withHistoryRowMissingRefreshExpiry_fallsBackToFullRefreshWindow() {
        // Dong cu (truoc migration V33) co the chua co refreshExpiresAt — khong
        // duoc NPE, va cung khong duoc bo qua viec chan phien.
        com.app.nino.model.entity.LoginHistory history = com.app.nino.model.entity.LoginHistory.builder()
            .id(9L).sessionId("sid-1").build();
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.getRefreshExpirationMs()).thenReturn(604_800_000L);
        when(loginHistoryRepo.findBySessionId("sid-1")).thenReturn(java.util.Optional.of(history));

        service.logout(1L, "access-1", null, null);

        verify(tokenBlacklistService).blacklist("sid-1", Duration.ofMillis(604_800_000L));
        assertNotNull(history.getRevokedAt());
    }

    @Test
    void logout_withLegacyAccessTokenWithoutSid_blacklistsRawTokenString() {
        // Token cu (mint truoc khi co claim "sid") -> getSid() tra ve null.
        // Truoc khi sua, nhanh blacklist bi bo qua hoan toan: logout tra 200 OK
        // nhung token van con hieu luc. Phai quay ve chan theo dung chuoi token.
        when(jwtTokenProvider.getSid("access-1")).thenReturn(null);
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));

        service.logout(1L, "access-1", null, null);

        verify(tokenBlacklistService).blacklist("access-1", Duration.ofMinutes(30));
        verifyNoMoreInteractions(tokenBlacklistService);
        verifyNoInteractions(loginHistoryRepo);
    }

    @Test
    void logout_withLegacyRefreshTokenWithoutSid_blacklistsRawRefreshTokenToo() {
        // Moi token duoc xet doc lap: ca access lan refresh token cu deu phai
        // bi chan theo chuoi token cua chinh no.
        when(jwtTokenProvider.getSid("access-1")).thenReturn(null);
        when(jwtTokenProvider.getSid("refresh-1")).thenReturn(null);
        when(jwtTokenProvider.validateToken("refresh-1")).thenReturn(true);
        when(jwtTokenProvider.getRemainingValidity("access-1")).thenReturn(Duration.ofMinutes(30));
        when(jwtTokenProvider.getRemainingValidity("refresh-1")).thenReturn(Duration.ofDays(7));

        service.logout(1L, "access-1", "refresh-1", null);

        verify(tokenBlacklistService).blacklist("access-1", Duration.ofMinutes(30));
        verify(tokenBlacklistService).blacklist("refresh-1", Duration.ofDays(7));
    }

    @Test
    void logout_withFcmToken_deletesThatDeviceForCallingUser() {
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.getRefreshExpirationMs()).thenReturn(604_800_000L);

        service.logout(1L, "access-1", null, "fcm-tok-1");

        verify(userDeviceRepo).deleteByFcmTokenAndUserId("fcm-tok-1", 1L);
    }

    @Test
    void logout_withoutFcmToken_doesNotTouchDevices() {
        when(jwtTokenProvider.getSid("access-1")).thenReturn("sid-1");
        when(jwtTokenProvider.getRefreshExpirationMs()).thenReturn(604_800_000L);

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

    // ── REFRESH TOKEN — session continuity ──────────────────────────────────

    @Test
    void refreshToken_reusesSameSidForNewTokenPair() {
        // Khong stub findBySessionId/getRefreshExpirationMs — mac dinh
        // findBySessionId tra ve Optional.empty(), nen nhanh cap nhat
        // refreshExpiresAt (dung getRefreshExpirationMs) khong chay toi; stub
        // thua se bi STRICT_STUBS bao UnnecessaryStubbingException.
        when(jwtTokenProvider.validateToken("old-refresh")).thenReturn(true);
        when(jwtTokenProvider.getTokenType("old-refresh")).thenReturn("refresh");
        when(jwtTokenProvider.getUserId("old-refresh")).thenReturn(1L);
        when(jwtTokenProvider.getSid("old-refresh")).thenReturn("sid-1");
        when(userRepo.findById(1L)).thenReturn(java.util.Optional.of(
            User.builder().id(1L).email("a@b.com").roles(new java.util.HashSet<>()).build()));

        service.refreshToken("old-refresh");

        verify(jwtTokenProvider).generateAccessToken(eq(1L), any(), eq("sid-1"));
        verify(jwtTokenProvider).generateRefreshToken(eq(1L), eq("sid-1"));
    }

    @Test
    void refreshToken_updatesRefreshExpiresAtOnMatchingHistoryRow() {
        com.app.nino.model.entity.LoginHistory history = com.app.nino.model.entity.LoginHistory.builder()
            .id(9L).sessionId("sid-1")
            .refreshExpiresAt(java.time.LocalDateTime.now().minusDays(1)) // gia lap han cu, se duoc troi toi
            .build();
        when(jwtTokenProvider.validateToken("old-refresh")).thenReturn(true);
        when(jwtTokenProvider.getTokenType("old-refresh")).thenReturn("refresh");
        when(jwtTokenProvider.getUserId("old-refresh")).thenReturn(1L);
        when(jwtTokenProvider.getSid("old-refresh")).thenReturn("sid-1");
        when(userRepo.findById(1L)).thenReturn(java.util.Optional.of(
            User.builder().id(1L).email("a@b.com").roles(new java.util.HashSet<>()).build()));
        when(jwtTokenProvider.getRefreshExpirationMs()).thenReturn(604_800_000L);
        when(loginHistoryRepo.findBySessionId("sid-1")).thenReturn(java.util.Optional.of(history));

        service.refreshToken("old-refresh");

        assertTrue(history.getRefreshExpiresAt().isAfter(java.time.LocalDateTime.now().plusDays(6)));
        verify(loginHistoryRepo).save(history);
    }

    @Test
    void refreshToken_rejectsAlreadyRevokedSession() {
        com.app.nino.model.entity.LoginHistory history = com.app.nino.model.entity.LoginHistory.builder()
            .id(9L).sessionId("sid-1").revokedAt(java.time.LocalDateTime.now().minusMinutes(5))
            .build();
        when(jwtTokenProvider.validateToken("old-refresh")).thenReturn(true);
        when(jwtTokenProvider.getTokenType("old-refresh")).thenReturn("refresh");
        when(jwtTokenProvider.getUserId("old-refresh")).thenReturn(1L);
        when(jwtTokenProvider.getSid("old-refresh")).thenReturn("sid-1");
        when(userRepo.findById(1L)).thenReturn(java.util.Optional.of(
            User.builder().id(1L).email("a@b.com").roles(new java.util.HashSet<>()).build()));
        when(loginHistoryRepo.findBySessionId("sid-1")).thenReturn(java.util.Optional.of(history));

        assertThrows(com.app.nino.exception.UnauthorizedException.class,
            () -> service.refreshToken("old-refresh"));
    }

    @Test
    void refreshToken_withNoMatchingHistoryRow_stillSucceeds() {
        // Phien tao truoc migration V33 (sessionId=null trong DB) hoac dong
        // da bi don rac — khong duoc de viec khong tim thay dong lich su lam
        // hong luong refresh binh thuong. getRefreshExpirationMs() KHONG duoc
        // stub o day vi khong co dong lich su nao de cap nhat refreshExpiresAt
        // — stub thua se bi STRICT_STUBS bao UnnecessaryStubbingException.
        when(jwtTokenProvider.validateToken("old-refresh")).thenReturn(true);
        when(jwtTokenProvider.getTokenType("old-refresh")).thenReturn("refresh");
        when(jwtTokenProvider.getUserId("old-refresh")).thenReturn(1L);
        when(jwtTokenProvider.getSid("old-refresh")).thenReturn("sid-1");
        when(userRepo.findById(1L)).thenReturn(java.util.Optional.of(
            User.builder().id(1L).email("a@b.com").roles(new java.util.HashSet<>()).build()));
        when(loginHistoryRepo.findBySessionId("sid-1")).thenReturn(java.util.Optional.empty());

        assertDoesNotThrow(() -> service.refreshToken("old-refresh"));
    }

    @Test
    void refreshToken_withNullSid_mintsFreshSidForNewTokenPair() {
        // Token cu tao truoc khi co claim "sid" — getSid() tra ve null. Truoc
        // khi sua, null do duoc truyen thang vao generateAccessToken/
        // generateRefreshToken; JJWT bo claim null nen cap token MOI cung khong
        // co sid — phien khong bao gio thu hoi duoc (refresh la cua so truot 7
        // ngay). Bay gio phai sinh sid moi: cap token vua tao luon co sid.
        when(jwtTokenProvider.validateToken("old-refresh")).thenReturn(true);
        when(jwtTokenProvider.getTokenType("old-refresh")).thenReturn("refresh");
        when(jwtTokenProvider.getUserId("old-refresh")).thenReturn(1L);
        when(jwtTokenProvider.getSid("old-refresh")).thenReturn(null);
        when(userRepo.findById(1L)).thenReturn(java.util.Optional.of(
            User.builder().id(1L).email("a@b.com").roles(new java.util.HashSet<>()).build()));

        assertDoesNotThrow(() -> service.refreshToken("old-refresh"));

        org.mockito.ArgumentCaptor<String> accessSid = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> refreshSid = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jwtTokenProvider).generateAccessToken(eq(1L), any(), accessSid.capture());
        verify(jwtTokenProvider).generateRefreshToken(eq(1L), refreshSid.capture());

        assertNotNull(accessSid.getValue());
        assertEquals(accessSid.getValue(), refreshSid.getValue()); // cung 1 phien
        assertDoesNotThrow(() -> java.util.UUID.fromString(accessSid.getValue()));
        // Sid vua sinh khong khop dong nao — va quan trong la KHONG bao gio goi
        // findBySessionId(null): tren repository THAT, Hibernate dich "= NULL"
        // thanh "IS NULL" va nem IncorrectResultSizeDataAccessException khi co
        // nhieu hon 1 dong session_id null.
        verify(loginHistoryRepo).findBySessionId(accessSid.getValue());
        verify(loginHistoryRepo, never()).findBySessionId(null);
        verifyNoMoreInteractions(loginHistoryRepo);
    }

    // ── LOGIN HISTORY — active/current-session flags ────────────────────────

    @Test
    void getLoginHistory_flagsActiveAndCurrentSessionCorrectly() {
        com.app.nino.model.entity.LoginHistory activeOther = com.app.nino.model.entity.LoginHistory.builder()
            .id(1L).isSuccess(true).sessionId("sid-other")
            .refreshExpiresAt(java.time.LocalDateTime.now().plusDays(3)).build();
        com.app.nino.model.entity.LoginHistory activeCurrent = com.app.nino.model.entity.LoginHistory.builder()
            .id(2L).isSuccess(true).sessionId("sid-current")
            .refreshExpiresAt(java.time.LocalDateTime.now().plusDays(3)).build();
        com.app.nino.model.entity.LoginHistory expired = com.app.nino.model.entity.LoginHistory.builder()
            .id(3L).isSuccess(true).sessionId("sid-expired")
            .refreshExpiresAt(java.time.LocalDateTime.now().minusDays(1)).build();
        com.app.nino.model.entity.LoginHistory revoked = com.app.nino.model.entity.LoginHistory.builder()
            .id(4L).isSuccess(true).sessionId("sid-revoked")
            .refreshExpiresAt(java.time.LocalDateTime.now().plusDays(3))
            .revokedAt(java.time.LocalDateTime.now().minusMinutes(5)).build();
        com.app.nino.model.entity.LoginHistory failed = com.app.nino.model.entity.LoginHistory.builder()
            .id(5L).isSuccess(false).build();

        when(loginHistoryRepo.findByUserIdOrderByLoginAtDesc(eq(1L), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(
                java.util.List.of(activeOther, activeCurrent, expired, revoked, failed)));

        var result = service.getLoginHistory(1L, 0, 20, "sid-current").getContent();

        assertTrue(result.get(0).getIsActive());
        assertFalse(result.get(0).getIsCurrentSession());
        assertTrue(result.get(1).getIsActive());
        assertTrue(result.get(1).getIsCurrentSession());
        assertFalse(result.get(2).getIsActive());
        assertFalse(result.get(3).getIsActive());
        assertFalse(result.get(4).getIsActive());
    }

    // ── REMOTE LOGOUT FROM LOGIN HISTORY ─────────────────────────────────────

    @Test
    void revokeLoginHistorySession_blacklistsSidWithRemainingTtlAndMarksRevoked() {
        com.app.nino.model.entity.LoginHistory history = com.app.nino.model.entity.LoginHistory.builder()
            .id(9L).user(User.builder().id(1L).build()).isSuccess(true).sessionId("sid-1")
            .refreshExpiresAt(java.time.LocalDateTime.now().plusDays(3)).build();
        when(loginHistoryRepo.findById(9L)).thenReturn(java.util.Optional.of(history));

        service.revokeLoginHistorySession(1L, 9L);

        org.mockito.ArgumentCaptor<Duration> ttlCaptor = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(tokenBlacklistService).blacklist(eq("sid-1"), ttlCaptor.capture());
        assertTrue(ttlCaptor.getValue().toDays() >= 2); // ~3 ngay, cho phep sai so nho
        assertNotNull(history.getRevokedAt());
        verify(loginHistoryRepo).save(history);
    }

    @Test
    void revokeLoginHistorySession_rejectsRowBelongingToAnotherUser() {
        com.app.nino.model.entity.LoginHistory history = com.app.nino.model.entity.LoginHistory.builder()
            .id(9L).user(User.builder().id(2L).build()).isSuccess(true).sessionId("sid-1")
            .refreshExpiresAt(java.time.LocalDateTime.now().plusDays(3)).build();
        when(loginHistoryRepo.findById(9L)).thenReturn(java.util.Optional.of(history));

        assertThrows(com.app.nino.exception.ResourceNotFoundException.class,
            () -> service.revokeLoginHistorySession(1L, 9L));
        verifyNoInteractions(tokenBlacklistService);
    }

    @Test
    void revokeLoginHistorySession_rejectsAlreadyInactiveRow() {
        com.app.nino.model.entity.LoginHistory history = com.app.nino.model.entity.LoginHistory.builder()
            .id(9L).user(User.builder().id(1L).build()).isSuccess(true).sessionId("sid-1")
            .refreshExpiresAt(java.time.LocalDateTime.now().minusDays(1)) // da het han
            .build();
        when(loginHistoryRepo.findById(9L)).thenReturn(java.util.Optional.of(history));

        assertThrows(com.app.nino.exception.BadRequestException.class,
            () -> service.revokeLoginHistorySession(1L, 9L));
        verifyNoInteractions(tokenBlacklistService);
    }

    @Test
    void revokeLoginHistorySession_rejectsMissingRow() {
        when(loginHistoryRepo.findById(9L)).thenReturn(java.util.Optional.empty());

        assertThrows(com.app.nino.exception.ResourceNotFoundException.class,
            () -> service.revokeLoginHistorySession(1L, 9L));
    }

    @Test
    void revokeLoginHistorySession_missingRowAndWrongOwnerRow_produceIdenticalErrorMessage() {
        // Ly do test nay ton tai: khong duoc de ke tan cong phan biet "id
        // khong ton tai" voi "id ton tai nhung khong phai cua minh" qua noi
        // dung thong bao loi — se lo duoc su ton tai cua row nguoi khac. Neu
        // sau nay ai do sua message rieng cho 1 trong 2 nhanh, test nay phai
        // fail ngay.
        when(loginHistoryRepo.findById(9L)).thenReturn(java.util.Optional.empty());
        Exception missingRowEx = assertThrows(com.app.nino.exception.ResourceNotFoundException.class,
            () -> service.revokeLoginHistorySession(1L, 9L));

        com.app.nino.model.entity.LoginHistory otherUsersHistory = com.app.nino.model.entity.LoginHistory.builder()
            .id(9L).user(User.builder().id(2L).build()).isSuccess(true).sessionId("sid-1")
            .refreshExpiresAt(java.time.LocalDateTime.now().plusDays(3)).build();
        when(loginHistoryRepo.findById(9L)).thenReturn(java.util.Optional.of(otherUsersHistory));
        Exception wrongOwnerEx = assertThrows(com.app.nino.exception.ResourceNotFoundException.class,
            () -> service.revokeLoginHistorySession(1L, 9L));

        assertEquals(missingRowEx.getMessage(), wrongOwnerEx.getMessage());
    }
}
