# Session Logout, Login Notification & Device-Name Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 3 backend bugs reported by the user: (1) logout doesn't actually revoke the session or stop push notifications, (2) a spurious "connected successfully" push fires on every login, (3) login history can't show a real device name.

**Architecture:** Add a Redis-backed JWT blacklist so `/auth/logout` can immediately revoke the calling access token (and, if supplied, the refresh token) instead of relying on passive up-to-7-day expiry; make logout also delete the caller's `UserDevice` row so push notifications stop immediately. Remove the unconditional `sendTestPush()` call that currently fires on every device (re)registration — which happens right after every login — since that in the login-success notification the user wants gone. Add an optional `deviceName` field to the login/Google-login requests and thread it into `LoginHistory` so login history can show the same human-readable device name already captured by `UserDevice.deviceName`.

**Tech Stack:** Spring Boot 3.3, Spring Data Redis (`RedisTemplate<String,Object>`, already configured in `RedisConfig`), JJWT 0.12.3, Flyway, JUnit 5 + Mockito.

**Spec:** No separate spec doc — requirements are the user's 5-item list from chat (2026-09-07); items #3 (Google Sign-In account chooser) and #4 (category icon picker) are confirmed out of scope for this repo (Android client UI, not backend) and have no tasks below.

## Global Constraints

- Follow existing code conventions in this repo: Vietnamese log/comment style matching surrounding code, `@Slf4j` + `log.info/warn/error` with `key=value` fields, Lombok `@Data`/`@Builder`, constructor injection via `@RequiredArgsConstructor` except where a class already uses field-level `@Value`/`@Autowired` (see `JwtTokenProvider`).
- TDD: write the failing test, watch it fail, write minimal code, watch it pass, then commit. No exceptions.
- Flyway migrations go in `src/main/resources/db/migration/`, next free version is `V29` (last committed is `V28__20260907_add_reminder_minutes_before.sql`).
- Redis failures must fail OPEN for the blacklist check (never block login/requests if Redis is down) — matches the existing `CacheErrorHandler` graceful-fallback pattern in `RedisConfig`.
- Run `./mvnw -q -o test` after every task; all tests must stay green before moving on.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/com/app/nino/controller/DeviceController.java` | Modify: stop sending a push on every device registration |
| `src/test/java/com/app/nino/controller/DeviceControllerTest.java` | Create: unit test asserting no push is sent on register |
| `src/main/java/com/app/nino/security/TokenBlacklistService.java` | Create: Redis-backed blacklist (`blacklist`/`isBlacklisted`), fail-open on Redis errors |
| `src/test/java/com/app/nino/security/TokenBlacklistServiceTest.java` | Create: unit test for blacklist service (mocked `RedisTemplate`) |
| `src/main/java/com/app/nino/security/JwtTokenProvider.java` | Modify: consult blacklist in `validateToken()`; add `getRemainingValidity(token)` |
| `src/test/java/com/app/nino/security/JwtTokenProviderTest.java` | Create: unit test for blacklist-aware validation + remaining-validity math + confirms sliding 7-day refresh-expiry behavior |
| `src/main/java/com/app/nino/model/dto/request/LogoutRequest.java` | Create: optional `refreshToken` + `fcmToken` body for `/auth/logout` |
| `src/main/java/com/app/nino/controller/AuthController.java` | Modify: `logout()` now takes the request body + extracts the caller's access token |
| `src/main/java/com/app/nino/service/AuthService.java` | Modify: add `logout(...)`; add `deviceName` handling to `login()`/`saveLoginHistory()` |
| `src/main/java/com/app/nino/service/GoogleAuthService.java` | Modify: add `deviceName` handling to `loginWithGoogle()`/`saveLoginHistory()` |
| `src/test/java/com/app/nino/service/AuthServiceTest.java` | Create: first test file for `AuthService` — covers `logout()` and `deviceName` propagation |
| `src/test/java/com/app/nino/service/GoogleAuthServiceTest.java` | Modify: add a `deviceName` propagation test |
| `src/main/java/com/app/nino/model/dto/request/LoginRequest.java` | Modify: add optional `deviceName` |
| `src/main/java/com/app/nino/model/dto/request/GoogleLoginRequest.java` | Modify: add optional `deviceName` |
| `src/main/java/com/app/nino/model/entity/LoginHistory.java` | Modify: add `deviceName` column mapping |
| `src/main/java/com/app/nino/model/dto/response/LoginHistoryResponse.java` | Modify: add `deviceName` + mapping |
| `src/main/resources/db/migration/V29__20260907_add_login_history_device_name.sql` | Create: adds `device_name` column to `login_histories` |

---

## Task 1: Stop the spurious "connected successfully" push on every device registration

This is the notification the user wants gone (item #2) — `DeviceController.registerDevice()` unconditionally calls `fcmService.sendTestPush(...)` every time a device (re)registers, which the endpoint's own doc-comment says happens "right after login". Removing this stops the notification without touching the device-registration logic itself (still needed for real reminder pushes to work).

**Files:**
- Modify: `src/main/java/com/app/nino/controller/DeviceController.java:62`
- Test: `src/test/java/com/app/nino/controller/DeviceControllerTest.java`

**Interfaces:**
- Consumes: existing `UserDeviceRepository`, `UserRepository`, `FcmService` (no signature changes)
- Produces: nothing new consumed by later tasks

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/app/nino/controller/DeviceControllerTest.java`:

```java
package com.app.nino.controller;

import com.app.nino.model.dto.request.DeviceTokenRequest;
import com.app.nino.model.entity.User;
import com.app.nino.model.entity.UserDevice;
import com.app.nino.repository.UserDeviceRepository;
import com.app.nino.repository.UserRepository;
import com.app.nino.service.FcmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceControllerTest {

    @Mock private UserDeviceRepository deviceRepo;
    @Mock private UserRepository userRepo;
    @Mock private FcmService fcmService;

    @InjectMocks
    private DeviceController controller;

    @Test
    void registerDevice_doesNotSendTestPush() {
        // Push "Kết nối thành công" trên MỌI lần đăng ký thiết bị — kể cả sau
        // mỗi lần login — chính là thông báo người dùng muốn bỏ. Đăng ký
        // thiết bị vẫn phải hoạt động (để nhận nhắc nhở thật sau này), chỉ
        // không tự bắn push xác nhận nữa.
        when(userRepo.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(deviceRepo.findByFcmToken("tok-1")).thenReturn(Optional.empty());

        DeviceTokenRequest req = new DeviceTokenRequest();
        req.setFcmToken("tok-1");
        req.setPlatform("ANDROID");
        req.setDeviceName("Pixel 8");

        controller.registerDevice(1L, req);

        verify(deviceRepo).save(any(UserDevice.class));
        verify(fcmService, never()).sendTestPush(anyString());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -o test -Dtest=DeviceControllerTest`
Expected: FAIL — `Wanted but not invoked: fcmService.sendTestPush(...)` is never satisfied as a failure (it's a `never()` check), so instead this fails with the actual current behavior: `fcmService.sendTestPush("tok-1")` WAS called, causing the `verify(fcmService, never())...` assertion to throw `Mockito...NeverWantedButInvoked` (or similar "Never wanted here" error). Confirm the failure message names `sendTestPush`, not a compile error.

- [ ] **Step 3: Remove the push call**

In `src/main/java/com/app/nino/controller/DeviceController.java`, delete these lines from `registerDevice()`:

```java
        // Gui push test ngay de client xac nhan hoat dong
        fcmService.sendTestPush(req.getFcmToken());
```

Also remove the now-unused `fcmService` field and its constructor wiring if nothing else in the class uses it — check first with a search; if `FcmService` becomes unused in this class, delete the `private final FcmService fcmService;` field and the import. (Keep the field if anything else in the class still references it.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -o test -Dtest=DeviceControllerTest`
Expected: PASS

- [ ] **Step 5: Run full suite and commit**

Run: `./mvnw -q -o test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/controller/DeviceController.java src/test/java/com/app/nino/controller/DeviceControllerTest.java
git commit -m "fix: stop sending a push notification on every device registration

DeviceController.registerDevice() called fcmService.sendTestPush()
unconditionally on every (re)registration, which — per the endpoint's
own doc comment — happens right after every login. That's the
\"connected successfully\" push the user gets on every successful
login and wants removed. Device registration itself is untouched;
only the confirmation push is gone.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVChxPQwgoh6Nh4H6Knf71"
```

---

## Task 2: Redis-backed JWT blacklist

Foundation for Task 3. `JwtTokenProvider` currently has no way to revoke a token before its natural expiry — logout is a client-side no-op today. This task adds a Redis-backed blacklist and wires it into `validateToken()`, so any endpoint behind `JwtAuthFilter` automatically rejects a revoked token with zero changes to the filter itself.

**Files:**
- Create: `src/main/java/com/app/nino/security/TokenBlacklistService.java`
- Test: `src/test/java/com/app/nino/security/TokenBlacklistServiceTest.java`
- Modify: `src/main/java/com/app/nino/security/JwtTokenProvider.java`
- Test: `src/test/java/com/app/nino/security/JwtTokenProviderTest.java`

**Interfaces:**
- Produces: `TokenBlacklistService.blacklist(String token, Duration ttl)`, `TokenBlacklistService.isBlacklisted(String token): boolean`
- Produces: `JwtTokenProvider.getRemainingValidity(String token): Duration`
- Consumes (Task 3): both of the above, plus existing `JwtTokenProvider.validateToken(String)`

- [ ] **Step 1: Write the failing test for `TokenBlacklistService`**

Create `src/test/java/com/app/nino/security/TokenBlacklistServiceTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -o test -Dtest=TokenBlacklistServiceTest`
Expected: FAIL with compilation error — `TokenBlacklistService` does not exist yet.

- [ ] **Step 3: Create `TokenBlacklistService`**

Create `src/main/java/com/app/nino/security/TokenBlacklistService.java`:

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -o test -Dtest=TokenBlacklistServiceTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Write the failing test for `JwtTokenProvider`**

Create `src/test/java/com/app/nino/security/JwtTokenProviderTest.java`:

```java
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
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./mvnw -q -o test -Dtest=JwtTokenProviderTest`
Expected: FAIL — compilation error (`tokenBlacklistService` field and `getRemainingValidity` method don't exist on `JwtTokenProvider` yet).

- [ ] **Step 7: Wire the blacklist into `JwtTokenProvider`**

In `src/main/java/com/app/nino/security/JwtTokenProvider.java`, add the field and use it in `validateToken()`, and add `getRemainingValidity()`:

```java
    @Value("${JWT_REFRESH_EXPIRATION:604800000}")
    private long refreshExpiration;

    @org.springframework.beans.factory.annotation.Autowired
    private TokenBlacklistService tokenBlacklistService;
```

```java
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] Token da het han: subject={}", e.getClaims().getSubject());
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JWT] Token khong hop le: {}", e.getMessage());
            return false;
        }
        if (tokenBlacklistService.isBlacklisted(token)) {
            log.warn("[JWT] Token da bi thu hoi (logout)");
            return false;
        }
        return true;
    }

    /** Thoi gian con lai truoc khi token tu het han — dung de dat TTL blacklist khi logout. */
    public java.time.Duration getRemainingValidity(String token) {
        Claims claims = Jwts.parser().verifyWith(getSigningKey()).build()
            .parseSignedClaims(token).getPayload();
        long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        return java.time.Duration.ofMillis(Math.max(remainingMs, 0));
    }
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./mvnw -q -o test -Dtest=JwtTokenProviderTest,TokenBlacklistServiceTest`
Expected: PASS (9 tests total)

- [ ] **Step 9: Run full suite and commit**

Run: `./mvnw -q -o test`
Expected: all green — check `GoogleAuthServiceTest` and any other test still mocking `JwtTokenProvider` wholesale still pass (they mock the whole class, so the new field doesn't affect them).

```bash
git add src/main/java/com/app/nino/security/TokenBlacklistService.java src/test/java/com/app/nino/security/TokenBlacklistServiceTest.java src/main/java/com/app/nino/security/JwtTokenProvider.java src/test/java/com/app/nino/security/JwtTokenProviderTest.java
git commit -m "feat: add Redis-backed JWT blacklist for immediate token revocation

JwtTokenProvider.validateToken() now consults a Redis blacklist
(TokenBlacklistService) before accepting a token, and exposes
getRemainingValidity() so callers can blacklist a token with a TTL
matching exactly its remaining natural lifetime (Redis expires the
entry itself — no cleanup job needed). Redis errors fail open so
infra hiccups never block auth. This is the foundation for making
/auth/logout actually revoke the session immediately (next commit)
instead of relying on passive up-to-7-day expiry.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVChxPQwgoh6Nh4H6Knf71"
```

---

## Task 3: `/auth/logout` revokes the session immediately and stops push notifications

This is the core fix for item #1. Today `/auth/logout` does nothing server-side (the access token stays valid until its natural expiry, and any registered `UserDevice`/FCM token is untouched — this is why notifications keep arriving after logout). After this task, logout blacklists the caller's access token (and, if the client sends it, the refresh token) immediately, and — if the client sends its `fcmToken` — deletes that device registration so push stops right away.

Note: the 7-day-inactivity auto-logout half of item #1 is already correctly implemented — `refreshToken()` reissues a new refresh token with a fresh 7-day TTL on every use (sliding window), so a refresh token genuinely expires if unused for 7 days. Task 2's `JwtTokenProviderTest.refreshToken_hasConfiguredSevenDayWindow` locks that in; no further change needed for that half.

**Files:**
- Create: `src/main/java/com/app/nino/model/dto/request/LogoutRequest.java`
- Modify: `src/main/java/com/app/nino/controller/AuthController.java`
- Modify: `src/main/java/com/app/nino/service/AuthService.java`
- Create: `src/test/java/com/app/nino/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `TokenBlacklistService.blacklist(String, Duration)` and `JwtTokenProvider.getRemainingValidity(String)` from Task 2; existing `UserDeviceRepository.deleteByFcmTokenAndUserId(String, Long)`
- Produces: `AuthService.logout(Long userId, String accessToken, String refreshToken, String fcmToken): void` — used only by `AuthController`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/app/nino/service/AuthServiceTest.java` (first test file for this service — scaffolding here is reused by Task 4):

```java
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -o test -Dtest=AuthServiceTest`
Expected: FAIL — compilation error (`AuthService.logout(...)` doesn't exist yet, `UserDeviceRepository`/`TokenBlacklistService` aren't constructor dependencies of `AuthService` yet, so `@InjectMocks` has nothing to bind them to).

- [ ] **Step 3: Implement `AuthService.logout()`**

In `src/main/java/com/app/nino/service/AuthService.java`, add the two new dependencies to the existing field list:

```java
    private final UserRepository         userRepo;
    private final RoleRepository         roleRepo;
    private final LoginHistoryRepository loginHistoryRepo;
    private final UserDeviceRepository   userDeviceRepo;        // NEW
    private final PasswordEncoder        passwordEncoder;
    private final JwtTokenProvider       jwtTokenProvider;
    private final TokenBlacklistService  tokenBlacklistService; // NEW
```

Add the imports:

```java
import com.app.nino.repository.UserDeviceRepository;
import com.app.nino.security.TokenBlacklistService;
```

Add the method (place it near `refreshToken()`):

```java
    // ── LOGOUT ────────────────────────────────────────────────────────────────
    // Thu hoi ngay access token dang goi request nay (va refresh token neu
    // client gui kem) thay vi cho no tu het han thu dong (toi 7 ngay); huy
    // dang ky thiet bi (fcmToken) neu client gui kem de dung push ngay lap tuc.
    public void logout(Long userId, String accessToken, String refreshToken, String fcmToken) {
        if (accessToken != null) {
            tokenBlacklistService.blacklist(accessToken, jwtTokenProvider.getRemainingValidity(accessToken));
        }
        if (refreshToken != null && jwtTokenProvider.validateToken(refreshToken)) {
            tokenBlacklistService.blacklist(refreshToken, jwtTokenProvider.getRemainingValidity(refreshToken));
        }
        if (fcmToken != null) {
            userDeviceRepo.deleteByFcmTokenAndUserId(fcmToken, userId);
        }
        log.info("[Auth] Dang xuat: userId={} thuHoiAccessToken={} thuHoiRefreshToken={} huyThietBi={}",
            userId, accessToken != null, refreshToken != null, fcmToken != null);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -o test -Dtest=AuthServiceTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Wire the controller**

Create `src/main/java/com/app/nino/model/dto/request/LogoutRequest.java`:

```java
package com.app.nino.model.dto.request;

import lombok.Data;

@Data
public class LogoutRequest {

    /** Tuy chon — neu gui kem, refresh token cung bi thu hoi ngay lap tuc
     *  thay vi cho no tu het han (toi 7 ngay). */
    private String refreshToken;

    /** Tuy chon — neu gui kem, huy dang ky thiet bi nay khoi FCM ngay lap
     *  tuc (dung push cho thiet bi nay tu thoi diem logout). */
    private String fcmToken;
}
```

In `src/main/java/com/app/nino/controller/AuthController.java`, replace the `logout()` method:

```java
    @PostMapping("/logout")
    @Operation(summary = "Đăng xuất",
               description = "Thu hồi ngay access token hiện tại (và refresh token/thiết bị nếu gửi kèm trong body).")
    public ResponseEntity<BaseResponse<?>> logout(
            @AuthenticationPrincipal Long userId,
            @RequestBody(required = false) LogoutRequest req,
            HttpServletRequest httpRequest) {
        authService.logout(
            userId,
            extractBearerToken(httpRequest),
            req != null ? req.getRefreshToken() : null,
            req != null ? req.getFcmToken() : null);
        return ResponseEntity.ok(BaseResponse.success(null, "Đăng xuất thành công"));
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
```

Add the import:

```java
import com.app.nino.model.dto.request.LogoutRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
```

- [ ] **Step 6: Run full suite and commit**

Run: `./mvnw -q -o test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/model/dto/request/LogoutRequest.java src/main/java/com/app/nino/controller/AuthController.java src/main/java/com/app/nino/service/AuthService.java src/test/java/com/app/nino/service/AuthServiceTest.java
git commit -m "fix: /auth/logout now revokes the session and stops push immediately

Previously logout was a stateless no-op — the access token stayed
valid until its natural (up to 24h) expiry and any registered
UserDevice/FCM token was left untouched, so push notifications kept
arriving after the user logged out. logout() now blacklists the
caller's access token (via TokenBlacklistService, Task 2) and, if the
client sends them, the refresh token and fcmToken too — deleting the
matching UserDevice so push stops right away.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVChxPQwgoh6Nh4H6Knf71"
```

---

## Task 4: Capture a real device name in login history

`LoginHistory` currently only derives `deviceType`/`os`/`browser` from the `User-Agent` header, which is `"Unknown"` for most native mobile HTTP clients — there's no human-readable device name (e.g. "Pixel 8", "iPhone 15 Pro") anywhere on it, even though `UserDevice.deviceName` already captures exactly that from a *different* endpoint (`POST /users/me/devices`, called separately after login). This task adds the same optional `deviceName` field directly to the login requests so it's captured at the moment of login itself.

**Note for whoever owns the mobile client:** the client already collects a device name and sends it as `deviceName` in `DeviceTokenRequest` today — send that exact same value as the new optional `deviceName` field on `POST /auth/login` and `POST /auth/google` too. No new capability needs to be built client-side, just include the field on two more requests it already knows the value for.

**Files:**
- Create: `src/main/resources/db/migration/V29__20260907_add_login_history_device_name.sql`
- Modify: `src/main/java/com/app/nino/model/entity/LoginHistory.java`
- Modify: `src/main/java/com/app/nino/model/dto/request/LoginRequest.java`
- Modify: `src/main/java/com/app/nino/model/dto/request/GoogleLoginRequest.java`
- Modify: `src/main/java/com/app/nino/model/dto/response/LoginHistoryResponse.java`
- Modify: `src/main/java/com/app/nino/service/AuthService.java`
- Modify: `src/main/java/com/app/nino/service/GoogleAuthService.java`
- Modify: `src/test/java/com/app/nino/service/AuthServiceTest.java` (add tests)
- Modify: `src/test/java/com/app/nino/service/GoogleAuthServiceTest.java` (add test)

**Interfaces:**
- Consumes: `AuthServiceTest`/`GoogleAuthServiceTest` scaffolding from Task 3
- Produces: nothing consumed by later tasks (last task in this plan)

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/app/nino/service/AuthServiceTest.java` (new imports: `com.app.nino.model.dto.request.LoginRequest`, `com.app.nino.model.entity.LoginHistory`, `jakarta.servlet.http.HttpServletRequest`, `org.mockito.ArgumentCaptor`, `java.util.Optional`):

```java
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
```

(Uses `import static org.junit.jupiter.api.Assertions.assertEquals;` and `import static org.mockito.ArgumentMatchers.any;` already present from the setup in Step 1 of Task 3 — add `assertEquals` to the existing static import if not already there.)

Add to `src/test/java/com/app/nino/service/GoogleAuthServiceTest.java` (follow the existing test method style in that file — `@Test` methods already set up `googleIdTokenVerifier`/`googleIdToken` mocks in `@BeforeEach`):

```java
    @Test
    void loginWithGoogle_withDeviceName_savesItOnLoginHistory() throws Exception {
        when(googleIdToken.getPayload()).thenReturn(payloadWith("g-1", "a@b.com", "pic.jpg"));
        User user = User.builder().id(1L).email("a@b.com")
            .authProvider(User.AuthProvider.GOOGLE).status("ACT")
            .roles(new HashSet<>()).build();
        when(userRepo.findByGoogleId("g-1")).thenReturn(Optional.of(user));

        service.loginWithGoogle("id-token", httpRequest, "iPhone 15 Pro");

        org.mockito.ArgumentCaptor<com.app.nino.model.entity.LoginHistory> captor =
            org.mockito.ArgumentCaptor.forClass(com.app.nino.model.entity.LoginHistory.class);
        verify(loginHistoryRepo).save(captor.capture());
        assertEquals("iPhone 15 Pro", captor.getValue().getDeviceName());
    }
```

Add the import `import java.util.Optional;` if not already present in that file (it already imports `Optional` — reuse it), and add `import static org.junit.jupiter.api.Assertions.assertEquals;` if missing.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -o test -Dtest=AuthServiceTest,GoogleAuthServiceTest`
Expected: FAIL — compilation errors: `LoginRequest.setDeviceName(...)`, `LoginHistory.getDeviceName()`, and `GoogleAuthService.loginWithGoogle(String, HttpServletRequest, String)` (3-arg overload) don't exist yet.

- [ ] **Step 3: Add the migration and entity field**

Create `src/main/resources/db/migration/V29__20260907_add_login_history_device_name.sql`:

```sql
-- V29_20260907_add_login_history_device_name.sql
-- login_histories chi co deviceType/os/browser suy tu User-Agent (thuong la
-- "Unknown" voi HTTP client cua app mobile). UserDevice.deviceName da co san
-- ten thiet bi de doc duoc (VD "Pixel 8") nhung tu 1 API dang ky rieng, goi
-- SAU login -> khong gan duoc vao dung dong lich su dang nhap. Them cot nay
-- de client gui thang deviceName ngay luc login.

ALTER TABLE `login_histories`
    ADD COLUMN `device_name` VARCHAR(100) DEFAULT NULL AFTER `device_type`;
```

In `src/main/java/com/app/nino/model/entity/LoginHistory.java`, add the field:

```java
    @Column(name = "device_type", length = 30)
    private String deviceType;

    @Column(name = "device_name", length = 100)
    private String deviceName;
```

- [ ] **Step 4: Add `deviceName` to the request DTOs**

In `src/main/java/com/app/nino/model/dto/request/LoginRequest.java`:

```java
    @NotBlank(message = "password khong duoc de trong")
    private String password;

    /** Tuy chon — VD "Pixel 8", "iPhone 15 Pro" — hien thi trong lich su dang nhap. */
    private String deviceName;
```

In `src/main/java/com/app/nino/model/dto/request/GoogleLoginRequest.java`:

```java
    @NotBlank(message = "idToken khong duoc de trong")
    private String idToken;

    /** Tuy chon — VD "Pixel 8", "iPhone 15 Pro" — hien thi trong lich su dang nhap. */
    private String deviceName;
```

- [ ] **Step 5: Thread `deviceName` through `AuthService`**

In `src/main/java/com/app/nino/service/AuthService.java`, update `login()` and both `saveLoginHistory` call sites plus the helper's signature:

```java
        handleSuccessLogin(user, ip);
        saveLoginHistory(user, ip, userAgent, req.getDeviceName(), true, null);
```

Update the three earlier `saveLoginHistory(user, ip, userAgent, false, ...)` calls in `login()` (account inactive / locked / wrong password) to also pass `req.getDeviceName()`:

```java
            saveLoginHistory(user, ip, userAgent, req.getDeviceName(), false, LoginHistory.FailureReason.ACCOUNT_INACTIVE);
```
```java
            saveLoginHistory(user, ip, userAgent, req.getDeviceName(), false, LoginHistory.FailureReason.ACCOUNT_LOCKED);
```
```java
            saveLoginHistory(user, ip, userAgent, req.getDeviceName(), false, LoginHistory.FailureReason.WRONG_PASSWORD);
```

Update the helper:

```java
    private void saveLoginHistory(User user, String ip, String userAgent, String deviceName,
                                   boolean success, LoginHistory.FailureReason reason) {
        LoginHistory history = LoginHistory.builder()
            .user(user)
            .ipAddress(ip)
            .userAgent(userAgent)
            .deviceType(DeviceParser.parseDeviceType(userAgent))
            .deviceName(deviceName)
            .os(DeviceParser.parseOs(userAgent))
            .browser(DeviceParser.parseBrowser(userAgent))
            .isSuccess(success)
            .failureReason(reason)
            .build();
        loginHistoryRepo.save(history);
    }
```

- [ ] **Step 6: Thread `deviceName` through `GoogleAuthService`**

In `src/main/java/com/app/nino/service/GoogleAuthService.java`, change `loginWithGoogle` to accept `deviceName` and update the caller in `AuthController`:

```java
    @Transactional
    public AuthResponse loginWithGoogle(String idToken, HttpServletRequest httpRequest, String deviceName) {
```

```java
        String ip = DeviceParser.getClientIp(httpRequest);
        handleSuccessLogin(user, ip);
        saveLoginHistory(user, ip, httpRequest.getHeader("User-Agent"), deviceName);
```

```java
    private void saveLoginHistory(User user, String ip, String userAgent, String deviceName) {
        LoginHistory history = LoginHistory.builder()
            .user(user)
            .ipAddress(ip)
            .userAgent(userAgent)
            .deviceType(DeviceParser.parseDeviceType(userAgent))
            .deviceName(deviceName)
            .os(DeviceParser.parseOs(userAgent))
            .browser(DeviceParser.parseBrowser(userAgent))
            .isSuccess(true)
            .build();
        loginHistoryRepo.save(history);
    }
```

In `src/main/java/com/app/nino/controller/AuthController.java`, update the `/auth/google` handler's call:

```java
    @PostMapping("/google")
    @Operation(summary = "Đăng nhập / đăng ký bằng Google",
               description = "Client gửi idToken lấy từ Google Sign-In SDK.")
    public ResponseEntity<BaseResponse<?>> loginWithGoogle(
            @Valid @RequestBody GoogleLoginRequest req,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(
            BaseResponse.success(googleAuthService.loginWithGoogle(req.getIdToken(), httpRequest, req.getDeviceName())));
    }
```

- [ ] **Step 7: Add `deviceName` to the response**

In `src/main/java/com/app/nino/model/dto/response/LoginHistoryResponse.java`:

```java
    private String        deviceType;   // Mobile | Desktop | Tablet
    private String        deviceName;   // VD "Pixel 8" — tu client gui khi login, co the null (log cu)
    private String        os;           // Windows | macOS | Android | iOS
```

```java
            .deviceType(h.getDeviceType())
            .deviceName(h.getDeviceName())
            .os(h.getOs())
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./mvnw -q -o test -Dtest=AuthServiceTest,GoogleAuthServiceTest`
Expected: PASS (all tests including the 2 new ones)

- [ ] **Step 9: Run full suite and commit**

Run: `./mvnw -q -o test`
Expected: all green.

```bash
git add src/main/resources/db/migration/V29__20260907_add_login_history_device_name.sql src/main/java/com/app/nino/model/entity/LoginHistory.java src/main/java/com/app/nino/model/dto/request/LoginRequest.java src/main/java/com/app/nino/model/dto/request/GoogleLoginRequest.java src/main/java/com/app/nino/model/dto/response/LoginHistoryResponse.java src/main/java/com/app/nino/service/AuthService.java src/main/java/com/app/nino/service/GoogleAuthService.java src/main/java/com/app/nino/controller/AuthController.java src/test/java/com/app/nino/service/AuthServiceTest.java src/test/java/com/app/nino/service/GoogleAuthServiceTest.java
git commit -m "feat: capture device name in login history

LoginHistory only ever had deviceType/os/browser parsed from
User-Agent, which is \"Unknown\" for most native mobile HTTP clients.
UserDevice.deviceName already captures a real, human-readable device
name (e.g. \"Pixel 8\") but from a separate endpoint called after
login, so it never made it onto the login history row. LoginRequest
and GoogleLoginRequest now accept the same deviceName the mobile
client already sends to POST /users/me/devices — the mobile client
needs to start sending it here too (V29 migration adds the column;
no backend behavior depends on it being present, so this is
backward compatible with clients that don't send it yet).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVChxPQwgoh6Nh4H6Knf71"
```

---

## Self-Review

**Spec coverage:**
- Item #1 (session not permanent + notifications after logout) → Task 2 (blacklist infra) + Task 3 (logout revokes token + deletes device) + `JwtTokenProviderTest.refreshToken_hasConfiguredSevenDayWindow` documents the already-correct 7-day sliding inactivity expiry. Covered.
- Item #2 (remove login-success notification) → Task 1. Covered.
- Item #3 (Google account chooser) → confirmed Android-client-only, no backend task. Documented in header.
- Item #4 (more category icons) → confirmed backend already accepts free-text icons, no backend task. Documented in header.
- Item #5 (login history missing device name) → Task 4. Covered.

**Placeholder scan:** no TBD/TODO/"handle appropriately" — every step has literal code.

**Type consistency:** `TokenBlacklistService.blacklist(String, Duration)` / `isBlacklisted(String)` used identically in Task 2 and Task 3. `JwtTokenProvider.getRemainingValidity(String): Duration` used identically in Task 2 tests and Task 3's `AuthService.logout()`. `AuthService.logout(Long, String, String, String)` signature matches between Task 3's test and `AuthController`. `GoogleAuthService.loginWithGoogle(String, HttpServletRequest, String)` 3-arg signature matches between Task 4's test and the updated `AuthController` call site.

---

## Out of scope (confirmed, not backend)

- **Item #3** — Google Sign-In always resolving to `dautruongptit@gmail.com` without showing the account picker: this is Android-side `GoogleSignInClient`/Credential Manager configuration (e.g. a cached/"remembered" account, or `filterByAuthorizedAccounts` not forcing the chooser). `nino-api` only receives the resulting `idToken` — it has no say in whether the picker shows. Needs to be fixed in the Android app's sign-in code.
- **Item #4** — more icon choices when adding a category: `CreateCategoryRequest.icon` already accepts any string (emoji or icon name) with no server-side whitelist — verified in `CategoryService`. The icon choices a user sees are a fixed list rendered by the Android app's UI; adding more is a client-side change only.
