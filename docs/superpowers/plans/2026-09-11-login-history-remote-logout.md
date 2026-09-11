# Remote Device Logout from Login History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user revoke a previously logged-in device's session directly from the "Lịch sử đăng nhập" screen — tapping "Đăng xuất" on a past login immediately invalidates that session's tokens, even if they've since been refreshed.

**Architecture:** Add a `sid` (session id, UUID) claim to every JWT, generated once at login and carried unchanged through every `/auth/refresh` rotation. Switch token revocation from "blacklist the exact token string" to "blacklist the `sid`" — one write invalidates every access/refresh token that has ever carried it. Persist `session_id`/`refresh_expires_at`/`revoked_at` on the `login_histories` row so a specific row can be looked up and revoked from a new endpoint.

**Tech Stack:** Spring Boot 3.3, Spring Data JPA + Flyway (MySQL), Spring Data Redis (`TokenBlacklistService`, already in place), JJWT 0.12.3, JUnit 5 + Mockito (backend); Flutter/Dart, `provider`, `dio` (mobile).

**Spec:** `docs/superpowers/specs/2026-09-11-login-history-remote-logout-design.md`

## Global Constraints

- Follow existing code conventions: Vietnamese log/comment style matching surrounding code, `@Slf4j` + `log.info/warn/error` with `key=value` fields, Lombok `@Data`/`@Builder`, constructor injection via `@RequiredArgsConstructor` except `JwtTokenProvider` (field `@Value`/`@Autowired`, pre-existing pattern — do not change it).
- TDD: write the failing test, watch it fail, write minimal code, watch it pass. No exceptions.
- Flyway migrations go in `src/main/resources/db/migration/`, next free version is `V33` (last committed: `V32__20260911_fix_user_birth_column_types.sql`).
- Run `./mvnw -q test` after every backend task; `flutter analyze && flutter test` after every mobile task. All must stay green before moving on.
- A signature change to `JwtTokenProvider.generateAccessToken`/`generateRefreshToken` breaks every caller at compile time (Java, single module) — Tasks 2-4 below are sized around keeping the whole module compiling and green at the end of each task, not around minimizing lines changed per task.
- MySQL column type traps: this session already hit "Schema-validation: wrong column type" twice (`V24`→fix in `V25`, `V31`→fix in `V32`) from using `TINYINT` for a Java `Integer`. This plan's new columns are `VARCHAR`/`DATETIME` mapped to `String`/`LocalDateTime` — no numeric columns, so this particular trap does not apply, but Task 1's Step 4 verification (`NinoApiApplicationTests.contextLoads`) exists specifically to catch a mismatch like this immediately if one slips in.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V33__20260911_add_login_history_session_tracking.sql` | Create: adds `session_id`, `refresh_expires_at`, `revoked_at` to `login_histories` |
| `src/main/java/com/app/nino/model/entity/LoginHistory.java` | Modify: add the 3 new fields |
| `src/main/java/com/app/nino/repository/LoginHistoryRepository.java` | Modify: add `findBySessionId` |
| `src/main/java/com/app/nino/security/JwtTokenProvider.java` | Modify: `sid` claim on generate methods, `getSid()`, `getRefreshExpirationMs()`, sid-based blacklist check in `validateToken()` |
| `src/test/java/com/app/nino/security/JwtTokenProviderTest.java` | Modify: sid-aware test cases |
| `src/main/java/com/app/nino/service/AuthService.java` | Modify: `register()`/`login()`/`logout()`/`refreshToken()`/`getLoginHistory()` sid wiring; new `revokeLoginHistorySession()` |
| `src/test/java/com/app/nino/service/AuthServiceTest.java` | Modify: rewrite logout tests for sid; add login/refresh/revoke tests |
| `src/main/java/com/app/nino/service/GoogleAuthService.java` | Modify: `loginWithGoogle()` sid wiring |
| `src/test/java/com/app/nino/service/GoogleAuthServiceTest.java` | Modify: fix mock signatures; add sid test |
| `src/main/java/com/app/nino/security/JwtAuthFilter.java` | Modify: stash `authSid` request attribute |
| `src/main/java/com/app/nino/model/dto/response/LoginHistoryResponse.java` | Modify: add `isActive`, `isCurrentSession` |
| `src/main/java/com/app/nino/controller/UserController.java` | Modify: thread `currentSid` into `getMyLoginHistory`; add `POST /me/login-history/{id}/logout` |
| `mobile/lib/models/login_history.dart` | Modify: add `isActive`, `isCurrentSession` |
| `mobile/test/models/login_history_test.dart` | Modify: add parsing test |
| `mobile/lib/core/constants/api_constants.dart` | Modify: add URL helper for the new endpoint |
| `mobile/lib/services/auth_service.dart` | Modify: add `logoutLoginHistorySession(int id)` |
| `mobile/lib/providers/auth_provider.dart` | Modify: add `logoutLoginHistorySession(int id)` |
| `mobile/lib/ui/screens/profile/login_history_screen.dart` | Modify: badge/button UI, wiring |

(`mobile/` paths are relative to the `mobile` repo root, e.g. `D:\My PC\nino\source_code\mobile`; everything else is relative to the `nino-api` repo root.)

---

## Task 1: `LoginHistory` session-tracking columns

**Files:**
- Create: `src/main/resources/db/migration/V33__20260911_add_login_history_session_tracking.sql`
- Modify: `src/main/java/com/app/nino/model/entity/LoginHistory.java`
- Modify: `src/main/java/com/app/nino/repository/LoginHistoryRepository.java`

**Interfaces:**
- Produces: `LoginHistory.getSessionId()/setSessionId(String)`, `.getRefreshExpiresAt()/setRefreshExpiresAt(LocalDateTime)`, `.getRevokedAt()/setRevokedAt(LocalDateTime)`; `LoginHistoryRepository.findBySessionId(String): Optional<LoginHistory>`

No test-first step here — this task only adds columns/fields nothing yet reads or writes (Task 3 onward exercises them through `AuthServiceTest`). Verification is the full suite + `contextLoads` (schema-vs-entity mapping), per the Global Constraints note above.

- [ ] **Step 1: Create the migration**

```sql
-- V33__20260911_add_login_history_session_tracking.sql
-- login_histories la audit log thuan tuy, khong luu token/session nao — khong
-- co cach thu hoi tu xa 1 phien dang nhap tu dong nay. Them 3 cot de lam
-- duoc: session_id (claim "sid" cua JWT phien do, giu nguyen qua moi lan
-- refresh — xem JwtTokenProvider), refresh_expires_at (han refresh token
-- HIEN TAI cua phien, cap nhat lai moi lan refresh vi cua so 7 ngay truot
-- toi), revoked_at (thoi diem phien bi thu hoi — tu dang xuat thuong hoac
-- dang xuat tu xa). Dong cu (truoc migration nay) deu NULL o ca 3 cot —
-- khong the revoke duoc vi token cua chung chua tung co claim "sid".

ALTER TABLE `login_histories`
    ADD COLUMN `session_id`         VARCHAR(36) NULL COMMENT 'sid claim cua JWT phien nay, NULL cho dong that bai/truoc migration',
    ADD COLUMN `refresh_expires_at` DATETIME    NULL COMMENT 'Han refresh token hien tai (sau lan refresh gan nhat) cua phien nay',
    ADD COLUMN `revoked_at`         DATETIME    NULL COMMENT 'Thoi diem phien bi thu hoi, NULL = chua thu hoi',
    ADD INDEX `idx_login_histories_session_id` (`session_id`);
```

- [ ] **Step 2: Add the entity fields**

In `src/main/java/com/app/nino/model/entity/LoginHistory.java`, add after the `loginAt` field (before `@PrePersist`):

```java
    /** Claim "sid" cua JWT phat hanh o lan dang nhap nay — xem JwtTokenProvider.
     *  NULL cho dong that bai (khong co phien nao de theo doi) hoac dong tao
     *  truoc khi co migration nay. */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    /** Han refresh token HIEN TAI cua phien nay — cap nhat lai moi lan
     *  /auth/refresh thanh cong (cua so 7 ngay truot toi). */
    @Column(name = "refresh_expires_at")
    private LocalDateTime refreshExpiresAt;

    /** Thoi diem phien bi thu hoi (tu dang xuat thuong hoac dang xuat tu xa
     *  qua man Lich su dang nhap) — NULL nghia la chua thu hoi. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
```

- [ ] **Step 3: Add the repository finder**

In `src/main/java/com/app/nino/repository/LoginHistoryRepository.java`, add:

```java
    java.util.Optional<LoginHistory> findBySessionId(String sessionId);
```

- [ ] **Step 4: Run the full suite to verify the schema/entity mapping is correct**

Run: `./mvnw -q test -Dtest=NinoApiApplicationTests`
Expected: PASS. If it fails with `Schema-validation: wrong column type`, the migration's column type doesn't match the Java field type — fix the migration's column type (do not add a second migration to patch it yet, since `V33` hasn't been committed/shared at this point in the plan).

Run: `./mvnw -q test`
Expected: all green (nothing new exercises these fields yet, so this just confirms nothing broke).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V33__20260911_add_login_history_session_tracking.sql src/main/java/com/app/nino/model/entity/LoginHistory.java src/main/java/com/app/nino/repository/LoginHistoryRepository.java
git commit -m "feat: add session-tracking columns to login_histories

Adds session_id (JWT sid claim), refresh_expires_at, and revoked_at to
login_histories, plus a findBySessionId repository method. Nothing
writes or reads these yet — foundation for the sid-based token
revocation landing in the next few commits.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019uJ7sH3SUbfaMXgSX1HxRu"
```

---

## Task 2: `sid` claim in `JwtTokenProvider` + sid-based blacklist check

This changes `generateAccessToken`/`generateRefreshToken` signatures, which breaks every caller at compile time — so this task also updates `AuthService` and `GoogleAuthService` call sites with a **freshly generated, not-yet-persisted-anywhere** `sid` (real, complete code — just not linked to `LoginHistory` yet; that lands in Tasks 3-4). Functionally nothing changes yet: no `sid` is ever blacklisted anywhere, so `validateToken()`'s new check never trips.

**Files:**
- Modify: `src/main/java/com/app/nino/security/JwtTokenProvider.java`
- Modify: `src/test/java/com/app/nino/security/JwtTokenProviderTest.java`
- Modify: `src/main/java/com/app/nino/service/AuthService.java` (call sites only: `register()`, `login()`, `refreshToken()`)
- Modify: `src/main/java/com/app/nino/service/GoogleAuthService.java` (call site only: `loginWithGoogle()`)
- Modify: `src/test/java/com/app/nino/service/AuthServiceTest.java` (fix mock stub signature)
- Modify: `src/test/java/com/app/nino/service/GoogleAuthServiceTest.java` (fix mock stub signatures)

**Interfaces:**
- Consumes: Task 1's `LoginHistory` fields (not used yet, just available)
- Produces: `JwtTokenProvider.generateAccessToken(Long userId, Set<Role> roles, String sid): String`, `.generateRefreshToken(Long userId, String sid): String`, `.getSid(String token): String`, `.getRefreshExpirationMs(): long`

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/app/nino/security/JwtTokenProviderTest.java`, replace the whole file:

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
    void generateAccessToken_embedsGivenSid() {
        String token = provider.generateAccessToken(1L, Set.of(Role.builder().name("ROLE_USER").build()), "sid-1");

        assertEquals("sid-1", provider.getSid(token));
    }

    @Test
    void generateRefreshToken_embedsGivenSid() {
        String token = provider.generateRefreshToken(1L, "sid-1");

        assertEquals("sid-1", provider.getSid(token));
    }

    @Test
    void validateToken_returnsFalse_whenSidBlacklisted() {
        String token = provider.generateAccessToken(1L, Set.of(Role.builder().name("ROLE_USER").build()), "sid-1");
        when(tokenBlacklistService.isBlacklisted("sid-1")).thenReturn(true);

        assertFalse(provider.validateToken(token));
    }

    @Test
    void validateToken_returnsTrue_whenSidNotBlacklisted() {
        String token = provider.generateAccessToken(1L, Set.of(Role.builder().name("ROLE_USER").build()), "sid-1");
        when(tokenBlacklistService.isBlacklisted("sid-1")).thenReturn(false);

        assertTrue(provider.validateToken(token));
    }

    @Test
    void validateToken_blacklistingOneSid_alsoRejectsARefreshedTokenSharingIt() {
        // Day chinh la ly do dung "sid" thay vi chan theo dung chuoi token: 1
        // token MOI (gia lap ket qua cua /auth/refresh) mang cung sid van bi
        // chan, du chua bao gio duoc dua vao blacklist truc tiep.
        String original = provider.generateAccessToken(1L, Set.of(Role.builder().name("ROLE_USER").build()), "sid-1");
        String rotated = provider.generateAccessToken(1L, Set.of(Role.builder().name("ROLE_USER").build()), "sid-1");
        when(tokenBlacklistService.isBlacklisted("sid-1")).thenReturn(true);

        assertFalse(provider.validateToken(original));
        assertFalse(provider.validateToken(rotated));
    }

    @Test
    void getRemainingValidity_returnsApproximatelyConfiguredExpiration() {
        String token = provider.generateAccessToken(1L, Set.of(Role.builder().name("ROLE_USER").build()), "sid-1");

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
        String refreshToken = provider.generateRefreshToken(1L, "sid-1");

        assertEquals("refresh", provider.getTokenType(refreshToken));
        Duration remaining = provider.getRemainingValidity(refreshToken);
        assertTrue(remaining.toMillis() > REFRESH_MS - 5000);
        assertTrue(remaining.toMillis() <= REFRESH_MS);
    }

    @Test
    void getRefreshExpirationMs_returnsConfiguredValue() {
        assertEquals(REFRESH_MS, provider.getRefreshExpirationMs());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=JwtTokenProviderTest`
Expected: FAIL — compilation error (`generateAccessToken`/`generateRefreshToken` don't accept a 3rd/2nd `sid` argument yet, `getSid`/`getRefreshExpirationMs` don't exist).

- [ ] **Step 3: Update `JwtTokenProvider`**

In `src/main/java/com/app/nino/security/JwtTokenProvider.java`, replace `generateAccessToken`, `generateRefreshToken`, and `validateToken`, and add the two new accessors:

```java
    public String generateAccessToken(Long userId, Set<Role> roles, String sid) {
        List<String> roleNames = roles.stream().map(Role::getName).collect(Collectors.toList());

        String token = Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("roles", roleNames)
            .claim("type", "access")
            .claim("sid", sid)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
            .signWith(getSigningKey())
            .compact();
        log.debug("[JWT] Tao access token: userId={} roles={} sid={} expiresInMs={}", userId, roleNames, sid, jwtExpiration);
        return token;
    }

    public String generateRefreshToken(Long userId, String sid) {
        String token = Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("type", "refresh")
            .claim("sid", sid)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
            .signWith(getSigningKey())
            .compact();
        log.debug("[JWT] Tao refresh token: userId={} sid={} expiresInMs={}", userId, sid, refreshExpiration);
        return token;
    }

    public boolean validateToken(String token) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] Token da het han: subject={}", e.getClaims().getSubject());
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JWT] Token khong hop le: {}", e.getMessage());
            return false;
        }
        String sid = claims.get("sid", String.class);
        if (sid != null && tokenBlacklistService.isBlacklisted(sid)) {
            log.warn("[JWT] Phien da bi thu hoi: sid={}", sid);
            return false;
        }
        return true;
    }

    /** Claim "sid" — dinh danh phien dang nhap, giu nguyen qua moi lan
     *  refresh (khac voi token thay doi moi lan cap). Dung de blacklist ca
     *  access lan refresh token cua 1 phien chi bang 1 lan ghi. */
    public String getSid(String token) {
        Claims claims = Jwts.parser().verifyWith(getSigningKey()).build()
            .parseSignedClaims(token).getPayload();
        return claims.get("sid", String.class);
    }

    /** De AuthService/GoogleAuthService tinh refreshExpiresAt luc luu
     *  LoginHistory ma khong phai tu khai bao lai @Value nay o noi khac. */
    public long getRefreshExpirationMs() {
        return refreshExpiration;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q test -Dtest=JwtTokenProviderTest`
Expected: PASS (9 tests). The full module will not yet compile (`AuthService`/`GoogleAuthService` still call the old 2-arg/1-arg signatures) — that's expected until Step 5.

- [ ] **Step 5: Fix the now-broken call sites in `AuthService`**

In `src/main/java/com/app/nino/service/AuthService.java`:

`register()` (around line 100):
```java
        String sid = UUID.randomUUID().toString();
        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);
```

`login()` (around line 150, the success-path token generation — the `sid` used for `saveLoginHistory` lands in Task 3, this step only keeps things compiling):
```java
        String sid = UUID.randomUUID().toString();
        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);
```

`refreshToken()` (around line 171 — Task 5 changes this to reuse the *old* token's sid; this step only fixes the compile error):
```java
        String sid = UUID.randomUUID().toString();
        String newAccessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);
```

- [ ] **Step 6: Fix the now-broken call site in `GoogleAuthService`**

In `src/main/java/com/app/nino/service/GoogleAuthService.java` (around line 90):
```java
        String sid = java.util.UUID.randomUUID().toString();
        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);
```

- [ ] **Step 7: Fix the now-broken mock stubs in `AuthServiceTest`**

In `src/test/java/com/app/nino/service/AuthServiceTest.java`, `login_withDeviceName_savesItOnLoginHistory()` (around line 139-140):
```java
        when(jwtTokenProvider.generateAccessToken(any(), any(), any())).thenReturn("access-1");
        when(jwtTokenProvider.generateRefreshToken(any(), any())).thenReturn("refresh-1");
```

- [ ] **Step 8: Fix the now-broken mock stubs in `GoogleAuthServiceTest`**

In `src/test/java/com/app/nino/service/GoogleAuthServiceTest.java`, `setUp()` (around line 56-57):
```java
        lenient().when(jwtTokenProvider.generateAccessToken(any(), any(), any())).thenReturn("access-token");
        lenient().when(jwtTokenProvider.generateRefreshToken(any(), any())).thenReturn("refresh-token");
```

- [ ] **Step 9: Run the full suite**

Run: `./mvnw -q test`
Expected: all green (module compiles; every existing test still passes; the 3 new sid tests plus the 6 pre-existing `JwtTokenProviderTest` tests all pass — 9 total in that file).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/app/nino/security/JwtTokenProvider.java src/test/java/com/app/nino/security/JwtTokenProviderTest.java src/main/java/com/app/nino/service/AuthService.java src/main/java/com/app/nino/service/GoogleAuthService.java src/test/java/com/app/nino/service/AuthServiceTest.java src/test/java/com/app/nino/service/GoogleAuthServiceTest.java
git commit -m "feat: add sid claim to JWTs, blacklist by sid instead of raw token

generateAccessToken/generateRefreshToken now embed a caller-supplied
sid claim, and validateToken() checks the blacklist by sid instead of
by exact token string — one blacklist write now invalidates every
token (past or future, via /auth/refresh rotation) carrying that sid,
which a raw-token blacklist could never do since refresh mints an
entirely new, unrelated token string every time.

AuthService/GoogleAuthService call sites pass a freshly generated,
not-yet-persisted sid for now (register/login/refresh) — purely to
keep the build compiling. Nothing is actually revocable yet; that
lands in the next few commits as sid gets threaded into LoginHistory
and a real revoke endpoint.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019uJ7sH3SUbfaMXgSX1HxRu"
```

---

## Task 3: `AuthService` — persist real `sid` on login, blacklist by `sid` on logout

**Files:**
- Modify: `src/main/java/com/app/nino/service/AuthService.java` (`login()`, `saveLoginHistory()`, `logout()`)
- Modify: `src/test/java/com/app/nino/service/AuthServiceTest.java` (rewrite logout tests, add login test)

**Interfaces:**
- Consumes: `JwtTokenProvider.getSid(String)`, `.getRefreshExpirationMs()` (Task 2); `LoginHistory.setSessionId/setRefreshExpiresAt/setRevokedAt`, `LoginHistoryRepository.findBySessionId` (Task 1)
- Produces: `AuthService.logout(...)` now blacklists by sid — Task 7's `revokeLoginHistorySession` follows the identical pattern

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/app/nino/service/AuthServiceTest.java`, replace the 5 existing `// ── LOGOUT ──` tests (lines 40-86) with:

```java
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
```

Add `import static org.junit.jupiter.api.Assertions.assertNotNull;` to the existing static imports at the top (keep the existing `assertEquals` import too).

Also update `login_withDeviceName_savesItOnLoginHistory()` (added in an earlier session) — it currently only asserts `deviceName`; extend it to also assert the session fields now get populated:

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=AuthServiceTest`
Expected: FAIL — `logout()` still blacklists by raw token, not sid (verify mismatches); `login()` doesn't set `sessionId`/`refreshExpiresAt` on the saved `LoginHistory` (both assert `null`, failing the `assertNotNull` checks).

- [ ] **Step 3: Rewrite `logout()` and thread `sid`/`refreshExpiresAt` through `login()`**

In `src/main/java/com/app/nino/service/AuthService.java`, replace `logout()`:

```java
    // ── LOGOUT ────────────────────────────────────────────────────────────────
    // Thu hoi ngay ca access lan refresh token cua PHIEN NAY (theo sid, khong
    // phai tung chuoi token rieng le) — nen van hoat dong dung ke ca sau khi
    // client da /auth/refresh nhieu lan. Refresh token (neu con hop le) song
    // lau hon access token nen TTL blacklist uu tien lay tu no.
    @Transactional
    public void logout(Long userId, String accessToken, String refreshToken, String fcmToken) {
        String sid = null;
        Duration ttl = Duration.ZERO;
        if (accessToken != null) {
            sid = jwtTokenProvider.getSid(accessToken);
            ttl = jwtTokenProvider.getRemainingValidity(accessToken);
        }
        if (refreshToken != null && jwtTokenProvider.validateToken(refreshToken)) {
            if (sid == null) sid = jwtTokenProvider.getSid(refreshToken);
            ttl = jwtTokenProvider.getRemainingValidity(refreshToken);
        }
        if (sid != null) {
            tokenBlacklistService.blacklist(sid, ttl);
            loginHistoryRepo.findBySessionId(sid).ifPresent(history -> {
                history.setRevokedAt(LocalDateTime.now());
                loginHistoryRepo.save(history);
            });
        }
        if (fcmToken != null) {
            userDeviceRepo.deleteByFcmTokenAndUserId(fcmToken, userId);
        }
        log.info("[Auth] Dang xuat: userId={} sid={} huyThietBi={}", userId, sid, fcmToken != null);
    }
```

Add `import java.time.Duration;` to the imports.

Now thread a real, persisted `sid` through `login()`. Replace the success-path block (the `sid`/token-generation lines added in Task 2 Step 5, plus the `saveLoginHistory` call):

```java
        String sid = UUID.randomUUID().toString();
        LocalDateTime refreshExpiresAt = LocalDateTime.now().plus(Duration.ofMillis(jwtTokenProvider.getRefreshExpirationMs()));
        handleSuccessLogin(user, ip);
        saveLoginHistory(user, ip, userAgent, req.getDeviceName(), true, null, sid, refreshExpiresAt);
        log.info("[Auth] Login thanh cong: userId={} ip={}", user.getId(), ip);

        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);
```

Update the 3 failed-login `saveLoginHistory(...)` call sites earlier in `login()` (account inactive / locked / wrong password) to pass `null, null` for the two new parameters — failed logins have no session to track:

```java
            saveLoginHistory(user, ip, userAgent, req.getDeviceName(), false, LoginHistory.FailureReason.ACCOUNT_INACTIVE, null, null);
```
```java
            saveLoginHistory(user, ip, userAgent, req.getDeviceName(), false, LoginHistory.FailureReason.ACCOUNT_LOCKED, null, null);
```
```java
            saveLoginHistory(user, ip, userAgent, req.getDeviceName(), false, LoginHistory.FailureReason.WRONG_PASSWORD, null, null);
```

Update the `saveLoginHistory` helper's signature:

```java
    private void saveLoginHistory(User user, String ip, String userAgent, String deviceName,
                                   boolean success, LoginHistory.FailureReason reason,
                                   String sessionId, LocalDateTime refreshExpiresAt) {
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
            .sessionId(sessionId)
            .refreshExpiresAt(refreshExpiresAt)
            .build();
        loginHistoryRepo.save(history);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q test -Dtest=AuthServiceTest`
Expected: PASS (all tests in the file, including the rewritten logout ones and the extended login test).

- [ ] **Step 5: Run the full suite and commit**

Run: `./mvnw -q test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/service/AuthService.java src/test/java/com/app/nino/service/AuthServiceTest.java
git commit -m "feat: persist sid on login, revoke by sid on logout

login() now generates a real sid per session, stores it (plus the
refresh token's expiry) on the LoginHistory row, and embeds it in both
issued tokens. logout() extracts the sid from whichever token is
available (preferring the access token, falling back to the refresh
token) and blacklists that sid — using the refresh token's remaining
validity as the TTL when present, since it always outlives the access
token — then marks the matching LoginHistory row revoked. This is the
same primitive Task 7's remote-logout endpoint will reuse.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019uJ7sH3SUbfaMXgSX1HxRu"
```

---

## Task 4: `GoogleAuthService` — same real `sid` wiring for Google login

**Files:**
- Modify: `src/main/java/com/app/nino/service/GoogleAuthService.java`
- Modify: `src/test/java/com/app/nino/service/GoogleAuthServiceTest.java`

**Interfaces:**
- Consumes: same `JwtTokenProvider`/`LoginHistory` interfaces as Task 3, applied to the Google login path

- [ ] **Step 1: Write the failing test**

In `src/test/java/com/app/nino/service/GoogleAuthServiceTest.java`, extend `loginWithGoogle_withDeviceName_savesItOnLoginHistory()`:

```java
    @Test
    void loginWithGoogle_withDeviceName_savesItOnLoginHistory() throws Exception {
        when(googleIdToken.getPayload()).thenReturn(payloadWith("g-1", "a@b.com", "pic.jpg"));
        User user = User.builder().id(1L).email("a@b.com")
            .authProvider(User.AuthProvider.GOOGLE).status("ACT")
            .roles(new HashSet<>()).build();
        when(userRepo.findByGoogleId("g-1")).thenReturn(Optional.of(user));
        when(jwtTokenProvider.getRefreshExpirationMs()).thenReturn(604_800_000L);

        service.loginWithGoogle("id-token", httpRequest, "iPhone 15 Pro");

        org.mockito.ArgumentCaptor<com.app.nino.model.entity.LoginHistory> captor =
            org.mockito.ArgumentCaptor.forClass(com.app.nino.model.entity.LoginHistory.class);
        verify(loginHistoryRepo).save(captor.capture());
        assertEquals("iPhone 15 Pro", captor.getValue().getDeviceName());
        assertNotNull(captor.getValue().getSessionId());
        assertNotNull(captor.getValue().getRefreshExpiresAt());
    }
```

Add `import static org.junit.jupiter.api.Assertions.assertNotNull;` to the static imports.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=GoogleAuthServiceTest`
Expected: FAIL — `getSessionId()`/`getRefreshExpiresAt()` are `null` on the captured row.

- [ ] **Step 3: Thread `sid`/`refreshExpiresAt` through `loginWithGoogle()`**

In `src/main/java/com/app/nino/service/GoogleAuthService.java`, replace the success-path block and `saveLoginHistory` call:

```java
        String ip = DeviceParser.getClientIp(httpRequest);
        String sid = java.util.UUID.randomUUID().toString();
        java.time.LocalDateTime refreshExpiresAt =
            java.time.LocalDateTime.now().plus(java.time.Duration.ofMillis(jwtTokenProvider.getRefreshExpirationMs()));
        handleSuccessLogin(user, ip);
        saveLoginHistory(user, ip, httpRequest.getHeader("User-Agent"), deviceName, sid, refreshExpiresAt);
        log.info("[GoogleAuth] Login thanh cong: userId={} ip={}", user.getId(), ip);

        String accessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);
```

Update the `saveLoginHistory` helper:

```java
    private void saveLoginHistory(User user, String ip, String userAgent, String deviceName,
                                   String sessionId, java.time.LocalDateTime refreshExpiresAt) {
        LoginHistory history = LoginHistory.builder()
            .user(user)
            .ipAddress(ip)
            .userAgent(userAgent)
            .deviceType(DeviceParser.parseDeviceType(userAgent))
            .deviceName(deviceName)
            .os(DeviceParser.parseOs(userAgent))
            .browser(DeviceParser.parseBrowser(userAgent))
            .isSuccess(true)
            .sessionId(sessionId)
            .refreshExpiresAt(refreshExpiresAt)
            .build();
        loginHistoryRepo.save(history);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q test -Dtest=GoogleAuthServiceTest`
Expected: PASS (all tests in the file).

- [ ] **Step 5: Run the full suite and commit**

Run: `./mvnw -q test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/service/GoogleAuthService.java src/test/java/com/app/nino/service/GoogleAuthServiceTest.java
git commit -m "feat: persist sid on Google login too

Mirrors the previous commit's AuthService.login() change for
GoogleAuthService.loginWithGoogle() — a separate, duplicated login
path in this codebase that must not be missed or Google-authenticated
sessions would be silently unrevokable.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019uJ7sH3SUbfaMXgSX1HxRu"
```

---

## Task 5: `refreshToken()` reuses `sid`, slides `refreshExpiresAt`, rejects revoked sessions

**Files:**
- Modify: `src/main/java/com/app/nino/service/AuthService.java` (`refreshToken()`)
- Modify: `src/test/java/com/app/nino/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `LoginHistoryRepository.findBySessionId` (Task 1), `JwtTokenProvider.getSid` (Task 2)

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/app/nino/service/AuthServiceTest.java`:

```java
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
```

Add `import static org.junit.jupiter.api.Assertions.assertThrows;` and `import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;` and `import static org.junit.jupiter.api.Assertions.assertTrue;` to the static imports if not already present.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=AuthServiceTest`
Expected: FAIL — `refreshToken()` still generates a fresh random `sid` each call (from Task 2 Step 5's placeholder) instead of reusing the old one; it also never checks `revokedAt` or updates `refreshExpiresAt`.

- [ ] **Step 3: Implement the real `refreshToken()`**

In `src/main/java/com/app/nino/service/AuthService.java`, replace `refreshToken()`:

```java
    // ── REFRESH TOKEN ─────────────────────────────────────────────────────────
    @Transactional
    public AuthResponse refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)
                || !"refresh".equals(jwtTokenProvider.getTokenType(refreshToken))) {
            log.warn("[Auth] Refresh token khong hop le hoac sai loai token");
            throw new UnauthorizedException("Refresh token không hợp lệ hoặc đã hết hạn");
        }

        Long userId = jwtTokenProvider.getUserId(refreshToken);
        String sid = jwtTokenProvider.getSid(refreshToken);
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // sid da bi thu hoi (dang xuat tu xa) nhung Redis chua kip phan anh —
        // hang phong thu thu 2 ben canh validateToken() da kiem tra blacklist.
        loginHistoryRepo.findBySessionId(sid).ifPresent(history -> {
            if (history.getRevokedAt() != null) {
                throw new UnauthorizedException("Phiên đăng nhập đã bị thu hồi");
            }
            history.setRefreshExpiresAt(
                LocalDateTime.now().plus(Duration.ofMillis(jwtTokenProvider.getRefreshExpirationMs())));
            loginHistoryRepo.save(history);
        });

        String newAccessToken  = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);
        log.info("[Auth] Refresh token thanh cong: userId={} sid={}", userId, sid);

        return AuthResponse.builder()
            .accessToken(newAccessToken).refreshToken(newRefreshToken)
            .userId(user.getId()).fullName(user.getFullName()).email(user.getEmail())
            .build();
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q test -Dtest=AuthServiceTest`
Expected: PASS (all tests in the file).

- [ ] **Step 5: Run the full suite and commit**

Run: `./mvnw -q test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/service/AuthService.java src/test/java/com/app/nino/service/AuthServiceTest.java
git commit -m "feat: refresh keeps the same sid and slides the session's expiry

refreshToken() now extracts the sid from the presented refresh token
and reuses it — rather than minting an unrelated one — so a session
stays revocable by the same sid no matter how many times it has
refreshed. Also advances the matching LoginHistory row's
refreshExpiresAt by another full window (the sliding 7-day behavior
already existed for the token itself; now the bookkeeping tracks it
too, so remote-revoke's TTL calculation stays accurate), and rejects
refresh outright if the session was already revoked — a defense-in-
depth check alongside the sid-blacklist check validateToken() already
does.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019uJ7sH3SUbfaMXgSX1HxRu"
```

---

## Task 6: `LoginHistoryResponse` flags + expose the caller's current `sid`

**Files:**
- Modify: `src/main/java/com/app/nino/model/dto/response/LoginHistoryResponse.java`
- Modify: `src/main/java/com/app/nino/service/AuthService.java` (`getLoginHistory()`, add `isActive()` helper)
- Modify: `src/main/java/com/app/nino/security/JwtAuthFilter.java`
- Modify: `src/main/java/com/app/nino/controller/UserController.java`
- Modify: `src/test/java/com/app/nino/service/AuthServiceTest.java`

**Interfaces:**
- Produces: `LoginHistoryResponse.from(LoginHistory, String currentSid): LoginHistoryResponse`; `AuthService.getLoginHistory(Long userId, int page, int size, String currentSid): Page<LoginHistoryResponse>`; request attribute `"authSid"` set by `JwtAuthFilter` — Task 7 reuses the same `isActive()` helper

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/app/nino/service/AuthServiceTest.java`:

```java
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
```

Add `import static org.junit.jupiter.api.Assertions.assertFalse;` to the static imports if not already present.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q test -Dtest=AuthServiceTest`
Expected: FAIL — compilation error (`getLoginHistory` doesn't accept a 4th argument; `LoginHistoryResponse` has no `getIsActive()`/`getIsCurrentSession()`).

- [ ] **Step 3: Add the response fields and the `isActive` rule**

In `src/main/java/com/app/nino/model/dto/response/LoginHistoryResponse.java`:

```java
package com.app.nino.model.dto.response;

import com.app.nino.model.entity.LoginHistory;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data @Builder
public class LoginHistoryResponse {
    private Long          id;
    private String        ipAddress;
    private String        deviceType;   // Mobile | Desktop | Tablet
    private String        deviceName;   // VD "Pixel 8" — tu client gui khi login, co the null (log cu)
    private String        os;           // Windows | macOS | Android | iOS
    private String        browser;      // Chrome | Safari | Firefox | Edge
    private String        country;
    private Boolean       isSuccess;
    private String        failureReason;
    private LocalDateTime loginAt;
    private Boolean       isActive;         // Con the "Dang xuat" tu xa duoc khong — xem AuthService.isActive()
    private Boolean       isCurrentSession; // La chinh phien dang goi request nay

    public static LoginHistoryResponse from(LoginHistory h, boolean active, String currentSid) {
        return LoginHistoryResponse.builder()
            .id(h.getId())
            .ipAddress(h.getIpAddress())
            .deviceType(h.getDeviceType())
            .deviceName(h.getDeviceName())
            .os(h.getOs())
            .browser(h.getBrowser())
            .country(h.getCountry())
            .isSuccess(h.getIsSuccess())
            .failureReason(h.getFailureReason() != null ? h.getFailureReason().name() : null)
            .loginAt(h.getLoginAt())
            .isActive(active)
            .isCurrentSession(currentSid != null && currentSid.equals(h.getSessionId()))
            .build();
    }
}
```

- [ ] **Step 4: Compute `isActive` and thread `currentSid` in `AuthService`**

In `src/main/java/com/app/nino/service/AuthService.java`, replace `getLoginHistory()` and add the helper (place both near the existing `// ── LOGIN HISTORY ──` section):

```java
    // ── LOGIN HISTORY ─────────────────────────────────────────────────────────
    public Page<LoginHistoryResponse> getLoginHistory(Long userId, int page, int size, String currentSid) {
        return loginHistoryRepo.findByUserIdOrderByLoginAtDesc(userId, PageRequest.of(page, size))
            .map(h -> LoginHistoryResponse.from(h, isActive(h), currentSid));
    }

    /** true neu phien nay con "Dang xuat tu xa" duoc — dang nhap thanh cong,
     *  co sid (dong tao tu migration V33 tro di), chua bi thu hoi, va refresh
     *  token chua het han tu nhien. */
    private boolean isActive(LoginHistory h) {
        return Boolean.TRUE.equals(h.getIsSuccess())
            && h.getSessionId() != null
            && h.getRevokedAt() == null
            && h.getRefreshExpiresAt() != null
            && h.getRefreshExpiresAt().isAfter(LocalDateTime.now());
    }
```

- [ ] **Step 5: Expose the caller's `sid` as a request attribute**

In `src/main/java/com/app/nino/security/JwtAuthFilter.java`, add right after `request.setAttribute("authUserId", userId);`:

```java
                request.setAttribute("authSid", jwtTokenProvider.getSid(token));
```

- [ ] **Step 6: Thread it through the controller**

In `src/main/java/com/app/nino/controller/UserController.java`, update `getMyLoginHistory`:

```java
    @GetMapping("/me/login-history")
    @Operation(summary = "Lịch sử đăng nhập của mình")
    public ResponseEntity<BaseResponse<?>> getMyLoginHistory(
            @AuthenticationPrincipal Long userId,
            @RequestAttribute(name = "authSid", required = false) String currentSid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(BaseResponse.success(authService.getLoginHistory(userId, page, size, currentSid)));
    }
```

Add the import `import org.springframework.web.bind.annotation.RequestAttribute;` (or confirm the existing `import org.springframework.web.bind.annotation.*;` already covers it — it does, since `UserController` already uses a wildcard import; no new import line needed).

- [ ] **Step 7: Run the full suite and commit**

Run: `./mvnw -q test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/model/dto/response/LoginHistoryResponse.java src/main/java/com/app/nino/service/AuthService.java src/main/java/com/app/nino/security/JwtAuthFilter.java src/main/java/com/app/nino/controller/UserController.java src/test/java/com/app/nino/service/AuthServiceTest.java
git commit -m "feat: flag active/current-session rows in login history response

LoginHistoryResponse now carries isActive (can this row still be
revoked from the Login History screen?) and isCurrentSession (is this
literally the session serving this very request?). JwtAuthFilter
stashes the calling request's sid as a request attribute (same pattern
already used for authUserId) so the controller can pass it through.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019uJ7sH3SUbfaMXgSX1HxRu"
```

---

## Task 7: Remote-logout endpoint

**Files:**
- Modify: `src/main/java/com/app/nino/service/AuthService.java` (`revokeLoginHistorySession()`)
- Modify: `src/main/java/com/app/nino/controller/UserController.java`
- Modify: `src/test/java/com/app/nino/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `isActive()` (Task 6), `TokenBlacklistService.blacklist` (existing), `LoginHistoryRepository.findById` (from `JpaRepository`)
- Produces: `AuthService.revokeLoginHistorySession(Long userId, Long historyId): void`; `POST /users/me/login-history/{id}/logout`

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/app/nino/service/AuthServiceTest.java`:

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=AuthServiceTest`
Expected: FAIL — compilation error (`revokeLoginHistorySession` doesn't exist yet).

- [ ] **Step 3: Implement `revokeLoginHistorySession`**

In `src/main/java/com/app/nino/service/AuthService.java`, add near `getLoginHistory()`:

```java
    @Transactional
    public void revokeLoginHistorySession(Long userId, Long historyId) {
        LoginHistory history = loginHistoryRepo.findById(historyId)
            .orElseThrow(() -> new ResourceNotFoundException("LoginHistory", historyId));
        // Tra ve loi "khong ton tai" giong het truong hop id sai — khong lo
        // cho ke tan cong biet dong nay co ton tai nhung thuoc user khac.
        if (!history.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("LoginHistory", historyId);
        }
        if (!isActive(history)) {
            throw new BadRequestException("Phiên đăng nhập này đã hết hạn hoặc đã đăng xuất");
        }
        Duration ttl = Duration.between(LocalDateTime.now(), history.getRefreshExpiresAt());
        tokenBlacklistService.blacklist(history.getSessionId(), ttl);
        history.setRevokedAt(LocalDateTime.now());
        loginHistoryRepo.save(history);
        log.info("[Auth] Dang xuat tu xa qua Lich su dang nhap: userId={} historyId={} sid={}",
            userId, historyId, history.getSessionId());
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q test -Dtest=AuthServiceTest`
Expected: PASS (all tests in the file).

- [ ] **Step 5: Wire the controller endpoint**

In `src/main/java/com/app/nino/controller/UserController.java`, add after `getMyLoginHistory`:

```java
    @PostMapping("/me/login-history/{id}/logout")
    @Operation(summary = "Đăng xuất một thiết bị/phiên trước đó",
               description = "Thu hồi ngay lập tức phiên đăng nhập tương ứng với dòng lịch sử này.")
    public ResponseEntity<BaseResponse<?>> logoutLoginHistorySession(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        authService.revokeLoginHistorySession(userId, id);
        return ResponseEntity.ok(BaseResponse.success(null, "Đã đăng xuất thiết bị"));
    }
```

(`@PostMapping`/`@PathVariable` are already covered by the existing `import org.springframework.web.bind.annotation.*;`.)

- [ ] **Step 6: Run the full suite and commit**

Run: `./mvnw -q test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/service/AuthService.java src/main/java/com/app/nino/controller/UserController.java src/test/java/com/app/nino/service/AuthServiceTest.java
git commit -m "feat: POST /users/me/login-history/{id}/logout revokes a device remotely

revokeLoginHistorySession() looks up the history row, confirms it
belongs to the calling user (404s the same way a missing id would, to
avoid confirming another user's row exists) and is currently active,
then blacklists its sid for exactly its remaining refresh-token
lifetime and marks it revoked. This is the feature the user asked for:
logging out a previously logged-in device from the Login History
screen.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019uJ7sH3SUbfaMXgSX1HxRu"
```

---

## Task 8: Mobile — `LoginHistoryModel` gains `isActive`/`isCurrentSession`

**Files:**
- Modify: `mobile/lib/models/login_history.dart`
- Modify: `mobile/test/models/login_history_test.dart`

**Interfaces:**
- Produces: `LoginHistoryModel.isActive: bool`, `.isCurrentSession: bool` — Task 10's screen reads both

- [ ] **Step 1: Write the failing test**

Add to `mobile/test/models/login_history_test.dart` (inside a new `group`, after the existing `deviceLabel` group):

```dart
  group('LoginHistoryModel.fromJson — session flags', () {
    test('parses isActive and isCurrentSession when present', () {
      final model = LoginHistoryModel.fromJson({
        'id': 1,
        'loginAt': '2026-01-01T00:00:00',
        'isActive': true,
        'isCurrentSession': false,
      });
      expect(model.isActive, true);
      expect(model.isCurrentSession, false);
    });

    test('defaults isActive and isCurrentSession to false when absent (older server)', () {
      final model = LoginHistoryModel.fromJson({
        'id': 1,
        'loginAt': '2026-01-01T00:00:00',
      });
      expect(model.isActive, false);
      expect(model.isCurrentSession, false);
    });
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && flutter test test/models/login_history_test.dart`
Expected: FAIL — `The getter 'isActive' isn't defined for the type 'LoginHistoryModel'` (and same for `isCurrentSession`).

- [ ] **Step 3: Add the fields**

In `mobile/lib/models/login_history.dart`, replace the whole file:

```dart
class LoginHistoryModel {
  final int id;
  final String? ipAddress;
  final String? deviceType; // Mobile | Desktop | Tablet
  final String? deviceName; // VD "Google Pixel 8" — client gửi kèm lúc login, có thể null (log cũ)
  final String? os; // Windows | macOS | Android | iOS
  final String? browser;
  final String? country;
  final bool isSuccess;
  final String? failureReason;
  final DateTime loginAt;
  final bool isActive; // Có thể "Đăng xuất" từ xa được không (xem backend AuthService.isActive)
  final bool isCurrentSession; // Đây chính là phiên đang xem màn hình này

  const LoginHistoryModel({
    required this.id,
    this.ipAddress,
    this.deviceType,
    this.deviceName,
    this.os,
    this.browser,
    this.country,
    this.isSuccess = true,
    this.failureReason,
    required this.loginAt,
    this.isActive = false,
    this.isCurrentSession = false,
  });

  factory LoginHistoryModel.fromJson(Map<String, dynamic> json) {
    return LoginHistoryModel(
      id: json['id'] as int,
      ipAddress: json['ipAddress'] as String?,
      deviceType: json['deviceType'] as String?,
      deviceName: json['deviceName'] as String?,
      os: json['os'] as String?,
      browser: json['browser'] as String?,
      country: json['country'] as String?,
      isSuccess: json['isSuccess'] as bool? ?? true,
      failureReason: json['failureReason'] as String?,
      loginAt: DateTime.parse(json['loginAt'] as String),
      isActive: json['isActive'] as bool? ?? false,
      isCurrentSession: json['isCurrentSession'] as bool? ?? false,
    );
  }

  /// Nhãn thiết bị hiển thị. Ưu tiên deviceName (VD "Google Pixel 8") do
  /// client gửi lúc đăng nhập — chính xác hơn os/browser suy từ User-Agent,
  /// vốn không nhận diện được User-Agent mặc định của Dio ("Unknown - Unknown").
  /// Các log cũ (chưa có deviceName) rơi về os/browser như trước.
  String get deviceLabel {
    if (deviceName != null && deviceName!.isNotEmpty) return deviceName!;
    final parts = [
      if (os != null && os!.isNotEmpty && os != 'Unknown') os,
      if (browser != null && browser!.isNotEmpty && browser != 'Unknown') browser,
    ];
    if (parts.isEmpty) return deviceType ?? 'Không rõ thiết bị';
    return parts.join(' - ');
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && flutter test test/models/login_history_test.dart`
Expected: PASS (6 tests: 4 existing `deviceLabel` + 2 new).

- [ ] **Step 5: Run the full mobile suite and commit**

Run: `cd mobile && flutter analyze lib/models/login_history.dart && flutter test`
Expected: no new analyzer issues; all tests pass.

```bash
cd "D:\My PC\nino\source_code\mobile"
git add lib/models/login_history.dart test/models/login_history_test.dart
git commit -m "feat: parse isActive/isCurrentSession on login history entries

Backend now flags whether a login-history row still represents a live,
revocable session and whether it's the one serving the current
request. Both default to false so this stays compatible with a server
that hasn't deployed the corresponding change yet.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019uJ7sH3SUbfaMXgSX1HxRu"
```

---

## Task 9: Mobile — API call to revoke a session

**Files:**
- Modify: `mobile/lib/core/constants/api_constants.dart`
- Modify: `mobile/lib/services/auth_service.dart`
- Modify: `mobile/lib/providers/auth_provider.dart`

**Interfaces:**
- Consumes: nothing new (plain `Dio` POST)
- Produces: `AuthProvider.logoutLoginHistorySession(int id): Future<bool>` — Task 10's screen calls this

No dedicated unit test in this task — `AuthService`/`AuthProvider` network calls have no existing test coverage in this codebase (see `getLoginHistory`/`updateSettings`/`uploadAvatar`, all untested thin wrappers) and this follows the exact same shape. Verified instead by `flutter analyze` + the full suite staying green, consistent with that established convention.

- [ ] **Step 1: Add the URL helper**

In `mobile/lib/core/constants/api_constants.dart`, add near the existing `loginHistory` constant:

```dart
  static String logoutLoginHistorySession(int id) => '$apiPrefix/users/me/login-history/$id/logout';
```

- [ ] **Step 2: Add the service method**

In `mobile/lib/services/auth_service.dart`, add after `getLoginHistory`:

```dart
  Future<void> logoutLoginHistorySession(int id) async {
    await _dio.post(ApiConstants.logoutLoginHistorySession(id));
  }
```

- [ ] **Step 3: Add the provider method**

In `mobile/lib/providers/auth_provider.dart`, add after `updateProfile`:

```dart
  Future<bool> logoutLoginHistorySession(int id) async {
    _setLoading(true);
    try {
      await _authService.logoutLoginHistorySession(id);
      _setLoading(false);
      return true;
    } catch (e) {
      _setError(apiErrorMessage(e));
      return false;
    }
  }
```

- [ ] **Step 4: Run analyze and the full suite**

Run: `cd mobile && flutter analyze lib/core/constants/api_constants.dart lib/services/auth_service.dart lib/providers/auth_provider.dart`
Expected: no new issues.

Run: `cd mobile && flutter test`
Expected: all green (nothing new exercises this yet — Task 10 wires the UI).

- [ ] **Step 5: Commit**

```bash
cd "D:\My PC\nino\source_code\mobile"
git add lib/core/constants/api_constants.dart lib/services/auth_service.dart lib/providers/auth_provider.dart
git commit -m "feat: add API call to log out a device from login history

Thin Dio wrapper + AuthProvider loading/error wrapper, matching the
existing updateProfile/uploadAvatar pattern — no UI wired up yet.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019uJ7sH3SUbfaMXgSX1HxRu"
```

---

## Task 10: Mobile — Login History screen UI

**Files:**
- Modify: `mobile/lib/ui/screens/profile/login_history_screen.dart`

**Interfaces:**
- Consumes: `LoginHistoryModel.isActive/.isCurrentSession` (Task 8), `AuthProvider.logoutLoginHistorySession` (Task 9)

No new unit test — this is a `StatefulWidget` build-method change with no widget-test infrastructure in this codebase (confirmed earlier this session: `mobile/test/` has zero widget tests today). Verified by `flutter analyze` + `flutter test` staying green, matching how this session's earlier Home-screen decorative-circle change was verified (code review + full-suite green; no widget test scaffolding introduced solely for one screen when the rest of the app has none).

- [ ] **Step 1: Add the badge/button to each row**

In `mobile/lib/ui/screens/profile/login_history_screen.dart`, replace the `itemBuilder` closure body (the `return Container(...)` for each row) so the trailing success/failure chip is followed by a second row for the badge/button:

```dart
              itemBuilder: (context, index) {
                final item = history[index];
                final color = item.isSuccess ? AppColors.success : AppColors.error;
                return Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: isDark ? AppColors.cardDark : AppColors.cardLight,
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(color: Colors.grey.withValues(alpha: 0.15)),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Container(
                            padding: const EdgeInsets.all(10),
                            decoration: BoxDecoration(
                              color: color.withValues(alpha: 0.1),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Icon(Icons.phone_android_rounded, color: color),
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(item.deviceLabel, style: AppTextStyles.subtitle),
                                const SizedBox(height: 4),
                                if (item.ipAddress != null)
                                  Text(
                                    'IP: ${item.ipAddress}',
                                    style: AppTextStyles.bodySmall.copyWith(
                                      color: isDark ? AppColors.textSecondaryDark : AppColors.textSecondaryLight,
                                    ),
                                  ),
                                Text(
                                  _formatDate(item.loginAt),
                                  style: AppTextStyles.caption.copyWith(
                                    color: isDark ? AppColors.textSecondaryDark : AppColors.textSecondaryLight,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                            decoration: BoxDecoration(
                              color: color.withValues(alpha: 0.1),
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Text(
                              item.isSuccess ? '✓' : '✕',
                              style: TextStyle(color: color, fontSize: 16),
                            ),
                          ),
                        ],
                      ),
                      // Nhãn "Thiết bị này" hoặc nút "Đăng xuất" — chỉ 1 trong 2
                      // (hoặc không có gì với lượt đăng nhập thất bại/đã hết
                      // hạn/đã đăng xuất) — xem AuthService.isActive() ở backend.
                      if (item.isCurrentSession) ...[
                        const SizedBox(height: 10),
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                          decoration: BoxDecoration(
                            color: (isDark ? AppColors.textSecondaryDark : AppColors.textSecondaryLight).withValues(alpha: 0.12),
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Text('Thiết bị này',
                              style: AppTextStyles.caption.copyWith(
                                color: isDark ? AppColors.textSecondaryDark : AppColors.textSecondaryLight,
                              )),
                        ),
                      ] else if (item.isActive) ...[
                        const SizedBox(height: 10),
                        Align(
                          alignment: Alignment.centerRight,
                          child: TextButton(
                            onPressed: () => _logoutDevice(item.id),
                            style: TextButton.styleFrom(foregroundColor: AppColors.error),
                            child: const Text('Đăng xuất'),
                          ),
                        ),
                      ],
                    ],
                  ),
                );
              },
```

- [ ] **Step 2: Add the `_logoutDevice` handler**

In `mobile/lib/ui/screens/profile/login_history_screen.dart`, add this method to `_LoginHistoryScreenState`, right after `_reload()`:

```dart
  Future<void> _logoutDevice(int historyId) async {
    final provider = context.read<AuthProvider>();
    final success = await provider.logoutLoginHistorySession(historyId);
    if (!mounted) return;
    if (success) {
      showNinoToast(context, 'Đã đăng xuất thiết bị');
      await _reload();
    } else {
      showNinoToast(context, provider.error ?? 'Không thể đăng xuất thiết bị này');
    }
  }
```

Add the import `import 'package:mobile/ui/widgets/nino/nino_toast.dart';` at the top of the file (check first — `EditProfileScreen` imports it as `'../../widgets/nino/nino_toast.dart'`; use whichever relative form matches this file's existing import style, i.e. `import '../../widgets/nino/nino_toast.dart';`).

- [ ] **Step 3: Run analyze and the full suite**

Run: `cd mobile && flutter analyze lib/ui/screens/profile/login_history_screen.dart`
Expected: no new issues.

Run: `cd mobile && flutter test`
Expected: all green.

- [ ] **Step 4: Commit**

```bash
cd "D:\My PC\nino\source_code\mobile"
git add lib/ui/screens/profile/login_history_screen.dart
git commit -m "feat: log out a previously logged-in device from Login History

Each row now shows a \"Thiết bị này\" badge (the session serving the
current screen — no button, per the user's explicit choice to disable
self-logout from this list) or a red \"Đăng xuất\" button (any other
still-active session). Rows for failed logins or sessions that already
expired/logged out show neither, matching the backend's isActive flag.
Tapping the button calls the new revoke endpoint and reloads the list
so the row's state updates immediately.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_019uJ7sH3SUbfaMXgSX1HxRu"
```

---

## Self-Review

**Spec coverage:**
- §3.3 `sid` claim surviving refresh → Task 2 (claim + accessors), Task 5 (refresh reuses it). Covered.
- §4 migration + entity + derived `isActive` rule → Task 1 (columns/entity), Task 6 (`isActive()` helper). Covered.
- §5.1-5.2 `JwtTokenProvider`/`TokenBlacklistService` → Task 2. Covered (`TokenBlacklistService` needed no code change, only a doc-comment update, folded into Task 2 Step 3's surrounding change — not a separate task since there's no new behavior or test to write for it).
- §5.3 `AuthService` (register/login/refresh/logout/getLoginHistory/revoke) → Tasks 2-3 (login/logout), 5 (refresh), 6 (getLoginHistory), 7 (revoke). Covered.
- §5.4 `GoogleAuthService` → Task 4. Covered.
- §5.5 exposing current `sid` → Task 6. Covered.
- §5.6 new endpoint → Task 7. Covered.
- §5.7 `LoginHistoryResponse` fields → Task 6. Covered.
- §5.8 repository finder → Task 1. Covered.
- §6 mobile model/API/UI → Tasks 8-10. Covered.
- §7 testing strategy's specific test names → all present as named tests across Tasks 2-8 (occasionally renamed slightly for clarity, e.g. spec's `login_generatesSidAndPersistsOnSuccessfulLoginHistory` became an extension of the pre-existing `login_withDeviceName_savesItOnLoginHistory` in Task 3, since both exercise the same `login()` success path and splitting them would duplicate all the same mock setup).
- §9 out-of-scope items — no tasks added for them, as intended.

**Placeholder scan:** every step has literal, complete code; no TBD/TODO.

**Type consistency:** `JwtTokenProvider.generateAccessToken(Long, Set<Role>, String)` / `.generateRefreshToken(Long, String)` / `.getSid(String): String` / `.getRefreshExpirationMs(): long` used identically across Tasks 2-5. `AuthService.logout(Long, String, String, String)` signature unchanged from before this plan (only its body changes) — matches `AuthController`'s existing call, no controller change needed for Task 3. `AuthService.getLoginHistory(Long, int, int, String)` and `LoginHistoryResponse.from(LoginHistory, boolean, String)` signatures match between Task 6's test, its implementation, and Task 7 reusing the same `isActive()` private helper. `AuthService.revokeLoginHistorySession(Long, Long)` matches between Task 7's test and `UserController`. Mobile: `LoginHistoryModel.isActive`/`.isCurrentSession` (Task 8) match the field names `LoginHistoryScreen` reads in Task 10; `AuthProvider.logoutLoginHistorySession(int): Future<bool>` (Task 9) matches Task 10's call site.

**Scope check:** 10 tasks, each independently compilable-and-green at its end (Tasks 2 is the largest, unavoidably, since a Java signature change is atomic across the module — documented in Global Constraints so this isn't a surprise mid-plan). Reasonable for one plan; no further decomposition needed.
