# Remote Device Logout from Login History — Design

**Date:** 2026-09-11
**Status:** Approved by user, pending implementation plan.
**Repos affected:** `nino-api` (backend, most of the work) and `mobile` (Flutter client).

## 1. Goal

Let a user log out a *previously logged-in device* directly from the "Lịch sử đăng nhập" (Login History) screen — without needing that device present. Tapping "Đăng xuất" on a past login entry must invalidate that session's tokens immediately, so the next request from that device is rejected.

Also recorded in this session: current token lifetimes (unchanged by this work, documented here for reference):
- Access token: `JWT_EXPIRATION` = 86400000 ms = **24h**
- Refresh token: `JWT_REFRESH_EXPIRATION` = 604800000 ms = **7 ngày** (sliding — see §3.2)

## 2. Decisions locked in with the user (2026-09-11)

1. **Immediacy:** revoking a device blacklists *both* its access and refresh token right away (not just the refresh token, letting the access token ride out its ≤24h natural life).
2. **Active vs. inactive rows:** the Login History list distinguishes sessions that can still be revoked ("đang hoạt động") from ones that can't (failed logins, already-logged-out, naturally expired). The "Đăng xuất" action is only offered on active rows.
3. **Self-logout via the list is disabled:** the row for the device the user is *currently* using is labeled ("Thiết bị này") and carries no logout button — logging out the current device stays the existing in-app logout flow, not this screen.

## 3. Root problem this design has to solve

### 3.1 `LoginHistory` is an audit log, not a session record

`login_histories` (`LoginHistory.java`) captures IP/UA/device/timestamp/success — it holds **no reference to the tokens** issued for that login. There is currently no way to go from a history row back to "which token(s) are still live for this login."

### 3.2 Token revocation today is keyed by the exact token string, and refresh *rotates* silently

`TokenBlacklistService.blacklist(String token, Duration ttl)` / `isBlacklisted(String token)` key directly on the raw JWT string (see `docs/superpowers/plans/2026-09-07-session-logout-devicename-fixes.md`, Task 2). `JwtTokenProvider.validateToken()` checks the blacklist using that same raw string.

`AuthService.refreshToken()` (called from `/auth/refresh`, and automatically by the mobile client whenever the ≤24h access token expires) issues a **brand-new** access+refresh token pair on every call, with no claim linking it back to the previous pair or to the original `LoginHistory` row — and it never blacklists the refresh token it just consumed (a pre-existing minor gap, fixed as a side effect of this work since unifying the blacklist key forces us to touch this code path anyway).

**Consequence:** if we only captured "the token issued at login" on the `LoginHistory` row, revoking it would do nothing once the client has refreshed even once — which, given a 24h access-token life, happens on almost every session that lives more than a day.

### 3.3 Chosen fix: a stable `sid` (session id) claim that survives refresh

Add a `sid` claim (a `UUID` string) to every JWT (access and refresh), generated **once at login** and **carried forward unchanged** on every subsequent `/auth/refresh` call — unlike `jti`-style designs where the identifier changes every mint. `TokenBlacklistService` moves from "blacklist by raw token string" to "blacklist by `sid`": one write blacklists every token — past, current, and any future one minted by a refresh — that carries that `sid`, until the TTL we set expires.

This makes the existing self-logout flow (`AuthService.logout()`) and the new remote-logout flow use the exact same primitive.

## 4. Data model changes

### 4.1 Migration `V33__20260911_add_login_history_session_tracking.sql`

```sql
ALTER TABLE `login_histories`
    ADD COLUMN `session_id`        VARCHAR(36)  NULL COMMENT 'sid claim cua JWT phien nay, NULL cho dong that bai/dang nhap truoc migration nay',
    ADD COLUMN `refresh_expires_at` DATETIME    NULL COMMENT 'Thoi diem refresh token hien tai (sau cung, neu da refresh) het han — cap nhat lai moi lan /auth/refresh',
    ADD COLUMN `revoked_at`        DATETIME     NULL COMMENT 'Thoi diem phien bi thu hoi (tu dang xuat hoac dang xuat tu xa) — NULL = chua thu hoi',
    ADD INDEX `idx_login_histories_session_id` (`session_id`);
```

Existing rows (pre-migration) get `NULL` for all three columns and are simply never "active" — they can be viewed in history but never revoked. Acceptable: their tokens predate the `sid` claim entirely, so there is nothing addressable to revoke anyway.

### 4.2 `LoginHistory` entity

Add fields `sessionId` (String), `refreshExpiresAt` (LocalDateTime), `revokedAt` (LocalDateTime), mapped to the columns above.

### 4.3 Derived "active" rule (not stored, computed on read/write)

A row is **active** iff: `isSuccess == true && sessionId != null && revokedAt == null && refreshExpiresAt != null && refreshExpiresAt.isAfter(now)`.

## 5. Backend changes

### 5.1 `JwtTokenProvider`

- `generateAccessToken(Long userId, Set<Role> roles, String sid)` / `generateRefreshToken(Long userId, String sid)` — both add `.claim("sid", sid)`. (Signature change — every call site updates, see §5.6.)
- `getSid(String token): String` — new accessor, mirrors `getUserId`/`getTokenType`.
- `getRefreshExpirationMs(): long` — new accessor exposing the existing `refreshExpiration` field, so `AuthService`/`GoogleAuthService` can compute `refreshExpiresAt` without duplicating the `@Value("${JWT_REFRESH_EXPIRATION:604800000}")` binding in a second class.
- `validateToken()`: after signature/expiry checks pass, extract `sid` via the claims already parsed and check `tokenBlacklistService.isBlacklisted(sid)` instead of `isBlacklisted(token)`. A token with no `sid` claim (shouldn't happen post-rollout, but defensive) is treated as never-blacklistable-by-sid — falls through to valid, same as today for such tokens.

### 5.2 `TokenBlacklistService`

No signature change needed — `blacklist(String key, Duration ttl)` / `isBlacklisted(String key)` are already string-keyed; callers now pass a `sid` instead of a raw token. Redis key prefix (`auth:blacklist:`) stays the same, just holds `sid`s now instead of token strings. Doc comment updated to reflect this.

### 5.3 `AuthService`

- `register()`: the new `generateAccessToken`/`generateRefreshToken` signatures require a `sid` argument, so `register()` must generate one (`UUID.randomUUID().toString()`) too, even though `register()` saves no `LoginHistory` row today (unchanged — out of scope to add one). This `sid` is simply not linked to anything revocable via this feature, consistent with today's behavior where a freshly-registered session has no history row either.
- `login()`: generate `sid = UUID.randomUUID().toString()` right before token generation; pass into both `generateAccessToken`/`generateRefreshToken`; pass into `saveLoginHistory(...)` **only on the success path** (failed-login rows get `sessionId = null`, matching them never being "active"). Also compute and pass `refreshExpiresAt = LocalDateTime.now().plus(jwtTokenProvider.getRefreshExpirationMs(), ChronoUnit.MILLIS)`.
- `refreshToken(String refreshToken)`:
  - Reject if the session backing this refresh token is already revoked: after validating the token normally, look up `loginHistoryRepo.findBySessionId(sid)`; if found and `revokedAt != null`, throw `UnauthorizedException` (defense in depth — `isBlacklisted` should already have caught this, but a row lookup here also protects against any future code path that mints a `sid`-less token).
  - Reuse the **same `sid`** (extracted from the old refresh token) when minting the new access+refresh pair — do not generate a new one.
  - Update the matching `LoginHistory` row's `refreshExpiresAt = now + jwtTokenProvider.getRefreshExpirationMs()` (the sliding window advances every refresh — see §3.2). If no matching row is found (pre-migration session, or a race), proceed without updating anything — refreshing must never fail just because the history bookkeeping is stale.
- `logout(...)`: extract `sid` from the access token (fall back to the refresh token if no access token was supplied — mirrors current null-handling) and blacklist **that `sid`** (TTL = `getRemainingValidity` of whichever token had the longer remaining life, i.e. the refresh token's remaining time when present, since blacklisting by `sid` now covers both at once — no need to blacklist access and refresh separately anymore). Also look up the `LoginHistory` row by `sid` and set `revokedAt = now()` if found, so this device's own row correctly flips to "not active" in its own history list.
- New method `revokeLoginHistorySession(Long userId, Long historyId)`:
  ```java
  @Transactional
  public void revokeLoginHistorySession(Long userId, Long historyId) {
      LoginHistory history = loginHistoryRepo.findById(historyId)
          .orElseThrow(() -> new ResourceNotFoundException("LoginHistory", historyId));
      if (!history.getUser().getId().equals(userId)) {
          throw new ResourceNotFoundException("LoginHistory", historyId); // không lộ tồn tại của dòng thuộc user khác
      }
      if (!isActive(history)) {
          throw new BadRequestException("Phiên đăng nhập này đã hết hạn hoặc đã đăng xuất");
      }
      Duration ttl = Duration.between(LocalDateTime.now(), history.getRefreshExpiresAt());
      tokenBlacklistService.blacklist(history.getSessionId(), ttl);
      history.setRevokedAt(LocalDateTime.now());
      loginHistoryRepo.save(history);
  }
  ```
  (`isActive(LoginHistory)` — small private helper implementing the §4.3 rule, reused by `getLoginHistory()`'s response mapping too.)
- `getLoginHistory(Long userId, int page, int size, String currentSid)`: signature gains `currentSid` (the `sid` of the token authenticating *this* request — see §5.5), threaded into `LoginHistoryResponse.from(h, currentSid)` for each row.

### 5.4 `GoogleAuthService`

Same `sid`-generation-and-`refreshExpiresAt`-computation change as `AuthService.login()` (§5.3), applied to `loginWithGoogle()`/its `saveLoginHistory()` — this is a separate, duplicated login path (per the existing codebase structure) and must not be missed. `GoogleAuthService` already injects `JwtTokenProvider`, so `getRefreshExpirationMs()` is available the same way.

### 5.5 Exposing the caller's current `sid` to controllers

`JwtAuthFilter` already stashes `request.setAttribute("authUserId", userId)` for later readers. Add `request.setAttribute("authSid", jwtTokenProvider.getSid(token))` right next to it. `UserController.getMyLoginHistory()` picks it up via `@RequestAttribute(name = "authSid", required = false) String currentSid` and passes it through to `authService.getLoginHistory(userId, page, size, currentSid)`.

### 5.6 New endpoint

```
POST /users/me/login-history/{id}/logout
```
- `UserController`: `@PostMapping("/me/login-history/{id}/logout")`, `@AuthenticationPrincipal Long userId`, `@PathVariable Long id` → `authService.revokeLoginHistorySession(userId, id)` → `BaseResponse.success(null, "Đã đăng xuất thiết bị")`.
- Errors: `ResourceNotFoundException` → existing global handler → 404 (row missing or belongs to another user — same status for both, so the endpoint never confirms/denies another user's row exists). `BadRequestException` ("đã hết hạn hoặc đã đăng xuất") → existing global handler → 400.

### 5.7 `LoginHistoryResponse`

Add `isActive: Boolean` and `isCurrentSession: Boolean`. `from(LoginHistory h, String currentSid)` computes both (`isCurrentSession = currentSid != null && currentSid.equals(h.getSessionId())`).

### 5.8 `LoginHistoryRepository`

Add `Optional<LoginHistory> findBySessionId(String sessionId)`.

## 6. Mobile changes

- `LoginHistoryModel`: add `isActive`, `isCurrentSession` (both `bool`, default `false`, parsed from JSON).
- `ApiConstants`: add a helper for the per-row logout URL (`'$apiPrefix/users/me/login-history/$id/logout'`).
- `AuthService` (mobile): `Future<void> logoutLoginHistorySession(int id)` → `_dio.post(...)`.
- `AuthProvider`: `Future<bool> logoutLoginHistorySession(int id)` — loading/error wrapper matching the existing `updateProfile`/`uploadAvatar` pattern.
- `LoginHistoryScreen`:
  - Row for `isCurrentSession == true`: small "Thiết bị này" badge, no button.
  - Row for `isActive == true && isCurrentSession == false`: "Đăng xuất" text button; on tap, call the provider method, show a toast (`showNinoToast`) on success/failure, and reload the list (`_reload()`) on success so the row disappears from "active" state immediately.
  - Row otherwise (failed login, inactive/expired, or pre-migration row with no `sessionId`): unchanged from today (no button, no badge).

## 7. Testing strategy

Backend (JUnit + Mockito, matching existing `AuthServiceTest`/`GoogleAuthServiceTest`/`JwtTokenProviderTest` conventions):
- `JwtTokenProviderTest`: `sid` claim round-trips through `getSid()`; `validateToken()` returns false when the token's `sid` is blacklisted (even though the exact token string was never individually blacklisted); a refreshed token sharing the same `sid` as a blacklisted original is also rejected.
- `AuthServiceTest`:
  - `login_generatesSidAndPersistsOnSuccessfulLoginHistory` — success path only; failed-login rows get `sessionId == null`.
  - `refreshToken_reusesSameSidAcrossRotation` — capture the `sid` passed to `generateAccessToken`/`generateRefreshToken` on refresh and assert it equals the one extracted from the presented (old) refresh token.
  - `refreshToken_updatesRefreshExpiresAtOnMatchingHistoryRow`.
  - `refreshToken_rejectsAlreadyRevokedSession`.
  - `logout_blacklistsBySidNotRawToken` and `logout_marksMatchingHistoryRowRevoked`.
  - `revokeLoginHistorySession_blacklistsSidWithRemainingTtl_andMarksRevoked`.
  - `revokeLoginHistorySession_rejectsRowBelongingToAnotherUser` (expects `ResourceNotFoundException`).
  - `revokeLoginHistorySession_rejectsAlreadyInactiveRow` (expects `BadRequestException`).
  - `getLoginHistory_flagsIsActiveAndIsCurrentSessionCorrectly`.
- `GoogleAuthServiceTest`: mirror the `login_generatesSidAndPersistsOnSuccessfulLoginHistory` case for `loginWithGoogle()`.

Mobile (following the existing `test/models/*_test.dart` convention — model-level only, no widget test infra exists in this repo, see prior session's precedent):
- `LoginHistoryModel.fromJson` parses `isActive`/`isCurrentSession`, defaulting to `false` when absent (pre-migration rows / server not yet upgraded).

## 8. Rollout note

Backend must deploy before mobile — the new response fields are additive (mobile ignores unknown-to-it fields today regardless), but the new endpoint obviously must exist before the mobile button can call it. No mobile behavior changes until the mobile PR ships, so the two can land independently in either order without breaking the other — but the *feature* only becomes usable once both are live.

## 9. Explicitly out of scope

- Showing device logout as a push/toast on the *revoked* device (it will simply get a 401 on its next request, which the app already handles as "session expired, please log in again" via existing 401 handling — verified this is generic, not tied to this feature).
- Any UI for the failed-login rows beyond what exists today.
- Retroactively backfilling `session_id` for rows created before this migration — impossible (no `sid` was ever generated for those tokens).
