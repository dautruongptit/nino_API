# OTP Email Verification (Resend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a generic OTP send/verify flow delivered via Resend email, reusable for REGISTER/RESET_PASSWORD/RESET_PIN purposes. Wire REGISTER into the existing `register()` flow so new accounts require email verification before they can log in. RESET_PASSWORD/RESET_PIN stop at issuing a short-lived JWT for a future (separate) reset endpoint.

**Architecture:** OTP codes live only in Redis (SHA-256 hashed, never plaintext), keyed `auth:otp:{code|ratelimit|attempts}:{purpose}:{email}`. `OtpService` orchestrates rate-limiting, generation, hashing, and verification; `ResendEmailService` renders one of 3 Thymeleaf templates and posts to Resend's HTTP API via Spring Boot 3.3's built-in `RestClient`. `JwtTokenProvider` gains a new short-lived token type (`generateOtpToken`) reusing the existing `sid`/blacklist mechanism, so a future reset endpoint can invalidate it after one use with zero new infrastructure. `AuthService.register()` changes from immediate activation (`status="ACT"`) to `status="REG"` (already the entity's own default, already excluded by `User.canLogin()`) and no longer returns usable session tokens — verified in code inspection that `JwtAuthFilter` trusts token claims directly without re-checking `canLogin()` per request, so an unverified account must never receive a working access token.

**Tech Stack:** Spring Boot 3.3 / Java 17, MySQL + Flyway (no new migration needed), Redis (Lettuce), JUnit 5 + Mockito, Thymeleaf (new dependency), `RestClient` (already available via `spring-boot-starter-web`).

**Spec:** `docs/superpowers/specs/2026-09-17-otp-email-verification-design.md`

## Global Constraints

- Follow existing code conventions: Vietnamese log/comment style matching surrounding code, `@Slf4j` + `log.info/warn/error`, Lombok `@Data`/`@Builder`, constructor injection via `@RequiredArgsConstructor`.
- **Package layout is flat** — no `com.app.nino.email`/`com.app.nino.otp` packages. New classes go in the existing `service/`, `controller/`, `config/`, `exception/`, `model/entity/`, `model/dto/request/`, `model/dto/response/` packages, matching how `FcmService`/`GoogleAuthService` already live flat in `service/` despite being single-concern integrations.
- **No Flyway migration** — OTP state lives only in Redis; `User.status` already defaults to `"REG"` (`@Builder.Default private String status = "REG";`) and `UserStatus.REGISTERED`/`VERIFIED` codes already exist, just unused until this plan.
- **The OTP plaintext must never be logged or persisted anywhere** — only its SHA-256 hex hash touches Redis. Compare via `MessageDigest.isEqual(...)`, never `String.equals`.
- **`OtpService` is fail-closed** (Redis errors propagate as exceptions) — this is the opposite of `TokenBlacklistService`'s deliberate fail-open policy; do not copy that pattern here.
- **`app.*` config convention**: new keys nest under `app.resend.*`/`app.otp.*` in `application.yml`, consumed via `@Value("${app.resend.api-key}")`-style nested paths (matching `GoogleAuthConfig`/`FirebaseConfig`/`AuthRateLimitFilter`) — not the raw-env-var-name pattern `JwtTokenProvider` happens to use for its *existing* fields (that's a pre-existing outlier, not the convention to extend).
- **Testing**: pure Mockito unit tests only (`@ExtendWith(MockitoExtension.class)`, `@Mock`/`@InjectMocks`) — this codebase has zero embedded-Redis/Testcontainers/`@DataJpaTest` infrastructure anywhere (confirmed by search); `TokenBlacklistServiceTest`/`JwtTokenProviderTest`/`EventServiceTest`/`AuthServiceTest` are all this style. Do not introduce new test infrastructure.
- **No controller-level tests** — no `AuthControllerTest`/MockMvc test exists for any controller in this codebase; only service-layer Mockito tests. `OtpController` follows that precedent (verified via the full suite staying green + `OtpService`'s own thorough tests).
- Run `./mvnw -q test` after every task; all must stay green before moving on.

---

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` | Modify: add `spring-boot-starter-thymeleaf` |
| `src/main/resources/application.yml` | Modify: add `app.resend.*` / `app.otp.*` config |
| `src/main/java/com/app/nino/exception/TooManyRequestsException.java` | Create: 429-mapped exception |
| `src/main/java/com/app/nino/exception/GlobalExceptionHandler.java` | Modify: map `TooManyRequestsException` → 429 |
| `src/main/java/com/app/nino/service/AuthService.java` | Modify: `register()` sets status REG, no session tokens |
| `src/test/java/com/app/nino/service/AuthServiceTest.java` | Modify: test for the above |
| `src/main/java/com/app/nino/security/JwtTokenProvider.java` | Modify: add `generateOtpToken`, `getOtpEmail`, `getOtpPurpose` |
| `src/test/java/com/app/nino/security/JwtTokenProviderTest.java` | Modify: tests for the above |
| `src/main/java/com/app/nino/model/entity/OtpPurpose.java` | Create: `REGISTER`/`RESET_PASSWORD`/`RESET_PIN` enum |
| `src/main/resources/templates/email/otp-verify.html` | Create: registration OTP email template |
| `src/main/resources/templates/email/password-reset.html` | Create: password-reset OTP email template |
| `src/main/resources/templates/email/pin-reset.html` | Create: PIN-reset OTP email template |
| `src/main/java/com/app/nino/config/ResendConfig.java` | Create: `RestClient` bean for Resend API |
| `src/main/java/com/app/nino/service/ResendEmailService.java` | Create: renders template, calls Resend API |
| `src/test/java/com/app/nino/service/ResendEmailServiceTest.java` | Create: tests for the above |
| `src/main/java/com/app/nino/model/dto/response/OtpVerifyResponse.java` | Create: verify-result DTO |
| `src/main/java/com/app/nino/service/OtpService.java` | Create: request/verify OTP orchestration |
| `src/test/java/com/app/nino/service/OtpServiceTest.java` | Create: tests for the above |
| `src/main/java/com/app/nino/model/dto/request/OtpRequestRequest.java` | Create: `POST /auth/otp/request` body |
| `src/main/java/com/app/nino/model/dto/request/OtpVerifyRequest.java` | Create: `POST /auth/otp/verify` body |
| `src/main/java/com/app/nino/controller/OtpController.java` | Create: 2 endpoints under `/auth/otp` |

---

## Task 1: Config + `TooManyRequestsException`

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/app/nino/exception/TooManyRequestsException.java`
- Modify: `src/main/java/com/app/nino/exception/GlobalExceptionHandler.java`

**Interfaces:**
- Produces: `TooManyRequestsException(String message)`, a 429 mapping in `GlobalExceptionHandler`

No test-first step — no existing test file for `GlobalExceptionHandler` in this codebase to extend, and a 3-line exception class + 1 handler method has nothing meaningful to unit-test in isolation (verified indirectly by `OtpServiceTest`/`OtpControllerTest`-equivalent coverage in later tasks, and by the full suite + app context staying green). Matches this codebase's existing precedent of not testing trivial mapping code.

- [ ] **Step 1: Add the Thymeleaf dependency**

In `pom.xml`, add inside `<dependencies>`, next to the other `spring-boot-starter-*` entries:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-thymeleaf</artifactId>
        </dependency>
```

- [ ] **Step 2: Add the config block**

In `src/main/resources/application.yml`, inside the existing `app:` block (after the `rate-limit:` section, before `# ── ACTUATOR`):

```yaml
  # OTP qua Resend — dang ky/quen mat khau/quen PIN. api-key BAT BUOC,
  # khong co default (giong DB_PASSWORD/JWT_SECRET) — fail-fast neu quen cau hinh.
  resend:
    api-key: ${RESEND_API_KEY}
    from: "Nino <no-reply@nino.thongtinchinhhieu.site>"
  otp:
    ttl-minutes: ${OTP_TTL_MINUTES:5}
    rate-limit-seconds: ${OTP_RATE_LIMIT_SECONDS:60}
    max-verify-attempts: ${OTP_MAX_VERIFY_ATTEMPTS:5}
    reset-token-ttl-minutes: ${OTP_RESET_TOKEN_TTL_MINUTES:5}
```

- [ ] **Step 3: Create the exception class**

Create `src/main/java/com/app/nino/exception/TooManyRequestsException.java`:

```java
package com.app.nino.exception;

public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Map it to HTTP 429**

In `src/main/java/com/app/nino/exception/GlobalExceptionHandler.java`, add right after `handleBadRequest(...)`:

```java
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<BaseResponse<?>> handleTooManyRequests(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(BaseResponse.error(e.getMessage(), "TOO_MANY_REQUESTS"));
    }
```

(Error code `"TOO_MANY_REQUESTS"` matches the literal string `AuthRateLimitFilter.writeTooManyRequests()` already writes for IP-level rate limiting — keeps both 429 sources consistent for clients.)

- [ ] **Step 5: Run the full suite**

Run: `./mvnw -q test`
Expected: all green (this task only adds new, unreferenced code — nothing should break; the Thymeleaf starter pulling in auto-configuration is verified here by the app context still loading in `NinoApiApplicationTests`).

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/main/java/com/app/nino/exception/TooManyRequestsException.java src/main/java/com/app/nino/exception/GlobalExceptionHandler.java
git commit -m "feat: add OTP/Resend config, Thymeleaf dependency, 429 exception

Foundation for the OTP email-verification feature: app.resend.*/app.otp.*
config, spring-boot-starter-thymeleaf for email templates, and
TooManyRequestsException mapped to HTTP 429 (matching the error code
AuthRateLimitFilter already uses for IP-level rate limiting). Nothing
uses this yet — OtpService lands in a later commit.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 2: `AuthService.register()` requires OTP verification

**Files:**
- Modify: `src/main/java/com/app/nino/service/AuthService.java`
- Modify: `src/test/java/com/app/nino/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `UserStatus.REGISTERED` (existing enum, unused until now)
- Produces: `register()` now returns an `AuthResponse` with `accessToken`/`refreshToken` both `null`

- [ ] **Step 1: Write the failing test**

In `src/test/java/com/app/nino/service/AuthServiceTest.java`, add the import:

```java
import com.app.nino.model.dto.request.RegisterRequest;
import com.app.nino.model.dto.response.AuthResponse;
import com.app.nino.model.entity.Role;
import org.mockito.ArgumentCaptor;
```

(add only the ones not already imported — check the existing import block first, this file already imports `User`/`Mock`/`InjectMocks`/etc.)

Then add, after the existing `@Mock`/`@InjectMocks` fields and before the first `// ── LOGOUT` test section:

```java

    // ── REGISTER ─────────────────────────────────────────────────────────
    @Test
    void register_setsStatusToRegistered_andReturnsNoSessionTokens() {
        RegisterRequest req = new RegisterRequest();
        req.setFullName("Nguyen Van A");
        req.setEmail("new@example.com");
        req.setPassword("password123");
        when(userRepo.existsByEmail("new@example.com")).thenReturn(false);
        when(roleRepo.findByName("ROLE_USER"))
            .thenReturn(java.util.Optional.of(Role.builder().name("ROLE_USER").build()));
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);

        AuthResponse response = service.register(req);

        verify(userRepo).save(savedUser.capture());
        assertEquals("REG", savedUser.getValue().getStatus());
        assertNull(response.getAccessToken());
        assertNull(response.getRefreshToken());
        assertEquals("new@example.com", response.getEmail());
        verifyNoInteractions(jwtTokenProvider);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q test -Dtest=AuthServiceTest#register_setsStatusToRegistered_andReturnsNoSessionTokens`
Expected: FAIL — current code sets `status="ACT"` (not `"REG"`) and calls `jwtTokenProvider.generateAccessToken(...)`/`generateRefreshToken(...)` (so `verifyNoInteractions(jwtTokenProvider)` fails).

- [ ] **Step 3: Update `register()`**

In `src/main/java/com/app/nino/service/AuthService.java`, replace the `register()` method body (lines ~77-111) with:

```java
    // ── REGISTER ──────────────────────────────────────────────────────────────
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        log.info("[Auth] Dang ky moi: email={}", req.getEmail());

        if (userRepo.existsByEmail(req.getEmail())) {
            log.warn("[Auth] Dang ky that bai — email da ton tai: email={}", req.getEmail());
            throw new BadRequestException("Email đã được sử dụng");
        }

        Role userRole = roleRepo.findByName("ROLE_USER")
            .orElseThrow(() -> new ResourceNotFoundException("Role", "ROLE_USER"));

        User user = User.builder()
            .username(req.getEmail())  // username chua dung toi noi khac — dung email cho chac chan unique
            .fullName(req.getFullName())
            .email(req.getEmail())
            .passwordHash(passwordEncoder.encode(req.getPassword()))
            // "REG" — tai khoan CHUA the dang nhap (User.canLogin() da loai
            // tru status nay) cho toi khi xac minh email qua OTP (xem
            // OtpService.verifyOtp, purpose=REGISTER). KHONG tra ve
            // accessToken/refreshToken ngay o day — JwtAuthFilter tin thang
            // claim trong token ma khong kiem tra lai canLogin() moi request,
            // nen phat token dung ngay tai day se cho phep tai khoan CHUA
            // xac minh dung duoc moi API nhu binh thuong — phai doi toi luc
            // OTP verify thanh cong moi phat token that.
            .roles(new HashSet<>(Set.of(userRole)))
            .build();

        userRepo.save(user);
        log.info("[Auth] Dang ky thanh cong, cho xac minh OTP: userId={} email={}", user.getId(), user.getEmail());

        return AuthResponse.builder()
            .userId(user.getId()).fullName(user.getFullName()).email(user.getEmail())
            .build();
    }
```

(Note what changed: dropped `.status("ACT")` entirely — `User.status` already `@Builder.Default`s to `"REG"` — and dropped the `sid`/`accessToken`/`refreshToken` generation block, so the response only carries `userId`/`fullName`/`email`.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q test -Dtest=AuthServiceTest`
Expected: PASS (all `AuthServiceTest` tests, including the new one).

- [ ] **Step 5: Run the full suite and commit**

Run: `./mvnw -q test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/service/AuthService.java src/test/java/com/app/nino/service/AuthServiceTest.java
git commit -m "fix: require OTP verification before a new account can log in

register() now leaves status at its default \"REG\" (User.canLogin()
already excludes it) instead of hardcoding \"ACT\", and no longer
returns session tokens — JwtAuthFilter trusts token claims without
re-checking canLogin() per request, so issuing a working token for an
unverified account would have let it use every API immediately.
OtpService.verifyOtp() (landing in a later commit) is what flips the
account to ACTIVE and issues real tokens once the user verifies.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 3: `JwtTokenProvider.generateOtpToken`

**Files:**
- Modify: `src/main/java/com/app/nino/security/JwtTokenProvider.java`
- Modify: `src/test/java/com/app/nino/security/JwtTokenProviderTest.java`

**Interfaces:**
- Produces: `JwtTokenProvider.generateOtpToken(String email, String purpose): String`, `getOtpEmail(String token): String`, `getOtpPurpose(String token): String`

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/app/nino/security/JwtTokenProviderTest.java`, add to `setUp()` (after the existing `ReflectionTestUtils.setField` calls):

```java
        ReflectionTestUtils.setField(provider, "otpResetTokenTtlMinutes", 5L);
```

Then add, after `getRefreshExpirationMs_returnsConfiguredValue`:

```java

    @Test
    void generateOtpToken_embedsEmailPurposeAndType() {
        String token = provider.generateOtpToken("user@example.com", "RESET_PASSWORD");

        assertEquals("user@example.com", provider.getOtpEmail(token));
        assertEquals("RESET_PASSWORD", provider.getOtpPurpose(token));
        assertEquals("otp_reset", provider.getTokenType(token));
    }

    @Test
    void generateOtpToken_embedsSidForFutureBlacklisting() {
        String token = provider.generateOtpToken("user@example.com", "RESET_PIN");

        assertNotNull(provider.getSid(token));
    }

    @Test
    void generateOtpToken_hasConfiguredFiveMinuteWindow() {
        String token = provider.generateOtpToken("user@example.com", "REGISTER");

        Duration remaining = provider.getRemainingValidity(token);
        long fiveMinutesMs = 5 * 60_000L;
        assertTrue(remaining.toMillis() > fiveMinutesMs - 5000);
        assertTrue(remaining.toMillis() <= fiveMinutesMs);
    }

    @Test
    void generateOtpToken_isRejectedByValidateToken_whenItsSidIsBlacklisted() {
        // Chung minh mot endpoint reset password/PIN (viec sau, ngoai pham vi
        // task nay) co the vo hieu token nay sau khi dung 1 lan bang chinh
        // TokenBlacklistService da co san — khong can code moi.
        String token = provider.generateOtpToken("user@example.com", "RESET_PASSWORD");
        String sid = provider.getSid(token);
        when(tokenBlacklistService.isBlacklisted(sid)).thenReturn(true);

        assertFalse(provider.validateToken(token));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=JwtTokenProviderTest`
Expected: FAIL to compile — `generateOtpToken`/`getOtpEmail`/`getOtpPurpose`/field `otpResetTokenTtlMinutes` don't exist yet.

- [ ] **Step 3: Implement the new methods**

In `src/main/java/com/app/nino/security/JwtTokenProvider.java`, add the field after `refreshExpiration`:

```java
    @Value("${app.otp.reset-token-ttl-minutes:5}")
    private long otpResetTokenTtlMinutes;
```

Then add, after `generateRefreshToken(...)`:

```java
    /** Token tam dung cho buoc doi mat khau/PIN SAU KHI da verify OTP thanh
     *  cong (viec do la 1 task rieng, ngoai pham vi nay) — KHONG dung de
     *  dang nhap: subject la EMAIL (khong phai userId, vi luong quen mat
     *  khau lam viec truoc khi xac dinh duoc userId da xac thuc), va
     *  type="otp_reset" khien JwtAuthFilter (chi chap nhan type="access")
     *  khong the nham lan token nay voi mot access token that. "sid" la 1
     *  UUID ngau nhien de endpoint doi mat khau/PIN co the blacklist token
     *  sau khi dung 1 lan, tai dung TokenBlacklistService da co san. */
    public String generateOtpToken(String email, String purpose) {
        String sid = UUID.randomUUID().toString();
        String token = Jwts.builder()
            .subject(email)
            .claim("type", "otp_reset")
            .claim("purpose", purpose)
            .claim("sid", sid)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + otpResetTokenTtlMinutes * 60_000))
            .signWith(getSigningKey())
            .compact();
        log.debug("[JWT] Tao otp reset token: purpose={} sid={} ttlMinutes={}", purpose, sid, otpResetTokenTtlMinutes);
        return token;
    }

    /** Email gan voi 1 otp_reset token — dung getSubject() thay vi
     *  getUserId() (subject o day la email, khong phai userId numeric). */
    public String getOtpEmail(String token) {
        Claims claims = Jwts.parser().verifyWith(getSigningKey()).build()
            .parseSignedClaims(token).getPayload();
        return claims.getSubject();
    }

    public String getOtpPurpose(String token) {
        Claims claims = Jwts.parser().verifyWith(getSigningKey()).build()
            .parseSignedClaims(token).getPayload();
        return claims.get("purpose", String.class);
    }
```

Also add the missing `import java.util.UUID;` if not already present (check the existing import list — `AuthService.java` already imports it, but `JwtTokenProvider.java` currently does not).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=JwtTokenProviderTest`
Expected: PASS.

- [ ] **Step 5: Run the full suite and commit**

Run: `./mvnw -q test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/security/JwtTokenProvider.java src/test/java/com/app/nino/security/JwtTokenProviderTest.java
git commit -m "feat: add JwtTokenProvider.generateOtpToken for OTP-gated resets

Short-lived (5min default), email-subject JWT with type=\"otp_reset\"
and a random sid — reuses the existing sid/TokenBlacklistService
mechanism so a future password/PIN-reset endpoint can invalidate it
after one use with no new infrastructure. Not yet called from
anywhere — OtpService lands in a later commit.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 4: `OtpPurpose` + email templates + `ResendEmailService`

**Files:**
- Create: `src/main/java/com/app/nino/model/entity/OtpPurpose.java`
- Create: `src/main/resources/templates/email/otp-verify.html`
- Create: `src/main/resources/templates/email/password-reset.html`
- Create: `src/main/resources/templates/email/pin-reset.html`
- Create: `src/main/java/com/app/nino/config/ResendConfig.java`
- Create: `src/main/java/com/app/nino/service/ResendEmailService.java`
- Test: `src/test/java/com/app/nino/service/ResendEmailServiceTest.java`

**Interfaces:**
- Produces: `OtpPurpose` enum (`REGISTER`, `RESET_PASSWORD`, `RESET_PIN`), `ResendEmailService.sendOtp(String toEmail, String otpCode, OtpPurpose purpose): void`

- [ ] **Step 1: Create the `OtpPurpose` enum**

Create `src/main/java/com/app/nino/model/entity/OtpPurpose.java` (lives alongside `UserStatus.java` — both are plain domain enums, not JPA entities, referenced by DTOs and services alike):

```java
package com.app.nino.model.entity;

public enum OtpPurpose {
    REGISTER, RESET_PASSWORD, RESET_PIN
}
```

- [ ] **Step 2: Create the email templates**

Create `src/main/resources/templates/email/otp-verify.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body style="margin:0;padding:0;background-color:#f5f5f5;font-family:Arial,Helvetica,sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background-color:#f5f5f5;padding:24px 0;">
    <tr>
      <td align="center">
        <table width="480" cellpadding="0" cellspacing="0" style="background-color:#ffffff;border-radius:12px;padding:32px;">
          <tr>
            <td style="font-size:20px;font-weight:bold;color:#1f2530;padding-bottom:12px;">Xác minh tài khoản Nino</td>
          </tr>
          <tr>
            <td style="font-size:14px;color:#4a4f57;line-height:1.6;padding-bottom:20px;">
              Cảm ơn bạn đã đăng ký Nino. Nhập mã dưới đây để xác minh tài khoản của bạn.
            </td>
          </tr>
          <tr>
            <td align="center" style="padding-bottom:20px;">
              <span th:text="${otpCode}" style="display:inline-block;font-size:32px;font-weight:bold;letter-spacing:8px;color:#ff5a5f;background-color:#fff1f1;padding:14px 24px;border-radius:8px;">123456</span>
            </td>
          </tr>
          <tr>
            <td style="font-size:13px;color:#8a94a6;line-height:1.6;padding-bottom:8px;">
              Mã có hiệu lực trong ít phút. Không chia sẻ mã này với bất kỳ ai, kể cả nhân viên hỗ trợ.
            </td>
          </tr>
          <tr>
            <td style="font-size:12px;color:#b4bbc7;padding-top:16px;border-top:1px solid #eeeeee;">
              Nếu bạn không thực hiện yêu cầu này, vui lòng bỏ qua email này.
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
```

Create `src/main/resources/templates/email/password-reset.html` (same structure, registration-specific copy swapped for password-reset copy):

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body style="margin:0;padding:0;background-color:#f5f5f5;font-family:Arial,Helvetica,sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background-color:#f5f5f5;padding:24px 0;">
    <tr>
      <td align="center">
        <table width="480" cellpadding="0" cellspacing="0" style="background-color:#ffffff;border-radius:12px;padding:32px;">
          <tr>
            <td style="font-size:20px;font-weight:bold;color:#1f2530;padding-bottom:12px;">Đặt lại mật khẩu Nino</td>
          </tr>
          <tr>
            <td style="font-size:14px;color:#4a4f57;line-height:1.6;padding-bottom:20px;">
              Bạn vừa yêu cầu đặt lại mật khẩu. Nhập mã dưới đây để tiếp tục.
            </td>
          </tr>
          <tr>
            <td align="center" style="padding-bottom:20px;">
              <span th:text="${otpCode}" style="display:inline-block;font-size:32px;font-weight:bold;letter-spacing:8px;color:#ff5a5f;background-color:#fff1f1;padding:14px 24px;border-radius:8px;">123456</span>
            </td>
          </tr>
          <tr>
            <td style="font-size:13px;color:#8a94a6;line-height:1.6;padding-bottom:8px;">
              Mã có hiệu lực trong ít phút. Không chia sẻ mã này với bất kỳ ai, kể cả nhân viên hỗ trợ.
            </td>
          </tr>
          <tr>
            <td style="font-size:12px;color:#b4bbc7;padding-top:16px;border-top:1px solid #eeeeee;">
              Nếu bạn không yêu cầu đặt lại mật khẩu, vui lòng bỏ qua email này và không chia sẻ mã.
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
```

Create `src/main/resources/templates/email/pin-reset.html` (same structure, PIN-reset copy):

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body style="margin:0;padding:0;background-color:#f5f5f5;font-family:Arial,Helvetica,sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background-color:#f5f5f5;padding:24px 0;">
    <tr>
      <td align="center">
        <table width="480" cellpadding="0" cellspacing="0" style="background-color:#ffffff;border-radius:12px;padding:32px;">
          <tr>
            <td style="font-size:20px;font-weight:bold;color:#1f2530;padding-bottom:12px;">Đặt lại mã PIN Nino</td>
          </tr>
          <tr>
            <td style="font-size:14px;color:#4a4f57;line-height:1.6;padding-bottom:20px;">
              Bạn vừa yêu cầu đặt lại mã PIN. Nhập mã dưới đây để tiếp tục.
            </td>
          </tr>
          <tr>
            <td align="center" style="padding-bottom:20px;">
              <span th:text="${otpCode}" style="display:inline-block;font-size:32px;font-weight:bold;letter-spacing:8px;color:#ff5a5f;background-color:#fff1f1;padding:14px 24px;border-radius:8px;">123456</span>
            </td>
          </tr>
          <tr>
            <td style="font-size:13px;color:#8a94a6;line-height:1.6;padding-bottom:8px;">
              Mã có hiệu lực trong ít phút. Không chia sẻ mã này với bất kỳ ai, kể cả nhân viên hỗ trợ.
            </td>
          </tr>
          <tr>
            <td style="font-size:12px;color:#b4bbc7;padding-top:16px;border-top:1px solid #eeeeee;">
              Nếu bạn không yêu cầu đặt lại mã PIN, vui lòng bỏ qua email này và không chia sẻ mã.
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
```

- [ ] **Step 3: Create the Resend `RestClient` bean**

Create `src/main/java/com/app/nino/config/ResendConfig.java`:

```java
package com.app.nino.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ResendConfig {

    @Bean
    public RestClient resendRestClient(@Value("${app.resend.api-key}") String apiKey) {
        return RestClient.builder()
            .baseUrl("https://api.resend.com")
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .build();
    }
}
```

- [ ] **Step 4: Write the failing tests**

Create `src/test/java/com/app/nino/service/ResendEmailServiceTest.java`:

```java
package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.model.entity.OtpPurpose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResendEmailServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private RestClient resendRestClient;
    @Mock
    private TemplateEngine templateEngine;

    @InjectMocks
    private ResendEmailService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "fromAddress", "Nino <no-reply@nino.thongtinchinhhieu.site>");
    }

    @Test
    void sendOtp_register_usesOtpVerifyTemplate() {
        when(templateEngine.process(eq("email/otp-verify"), any(Context.class))).thenReturn("<html>123456</html>");

        service.sendOtp("user@example.com", "123456", OtpPurpose.REGISTER);

        verify(templateEngine).process(eq("email/otp-verify"), any(Context.class));
    }

    @Test
    void sendOtp_resetPassword_usesPasswordResetTemplate() {
        when(templateEngine.process(eq("email/password-reset"), any(Context.class))).thenReturn("<html></html>");

        service.sendOtp("user@example.com", "654321", OtpPurpose.RESET_PASSWORD);

        verify(templateEngine).process(eq("email/password-reset"), any(Context.class));
    }

    @Test
    void sendOtp_resetPin_usesPinResetTemplate() {
        when(templateEngine.process(eq("email/pin-reset"), any(Context.class))).thenReturn("<html></html>");

        service.sendOtp("user@example.com", "111222", OtpPurpose.RESET_PIN);

        verify(templateEngine).process(eq("email/pin-reset"), any(Context.class));
    }

    @Test
    void sendOtp_passesOtpCodeIntoTemplateContext() {
        ArgumentCaptor<Context> ctxCaptor = ArgumentCaptor.forClass(Context.class);
        when(templateEngine.process(anyString(), ctxCaptor.capture())).thenReturn("<html></html>");

        service.sendOtp("user@example.com", "999888", OtpPurpose.REGISTER);

        assertEquals("999888", ctxCaptor.getValue().getVariable("otpCode"));
    }

    @Test
    void sendOtp_whenResendCallThrows_wrapsInBadRequestException() {
        when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html></html>");
        when(resendRestClient.post().uri("/emails").body(any()).retrieve().toBodilessEntity())
            .thenThrow(new RestClientException("boom"));

        assertThrows(BadRequestException.class,
            () -> service.sendOtp("user@example.com", "111111", OtpPurpose.REGISTER));
    }
}
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=ResendEmailServiceTest`
Expected: FAIL to compile — `ResendEmailService` doesn't exist yet.

- [ ] **Step 6: Implement `ResendEmailService`**

Create `src/main/java/com/app/nino/service/ResendEmailService.java`:

```java
package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.model.entity.OtpPurpose;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;

/** Gui email OTP qua Resend (https://resend.com) — render 1 trong 3
 *  template Thymeleaf tuy purpose, roi POST /emails. KHONG BAO GIO log
 *  otpCode duoi bat ky hinh thuc nao, ke ca DEBUG. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResendEmailService {

    private final RestClient resendRestClient;
    private final TemplateEngine templateEngine;

    @Value("${app.resend.from}")
    private String fromAddress;

    private record ResendEmailRequest(String from, List<String> to, String subject, String html) {}

    public void sendOtp(String toEmail, String otpCode, OtpPurpose purpose) {
        Context ctx = new Context();
        ctx.setVariable("otpCode", otpCode);
        String html = templateEngine.process(templateFor(purpose), ctx);

        ResendEmailRequest body = new ResendEmailRequest(fromAddress, List.of(toEmail), subjectFor(purpose), html);

        try {
            resendRestClient.post()
                .uri("/emails")
                .body(body)
                .retrieve()
                .toBodilessEntity();
            log.info("[ResendEmail] Da gui OTP: to={} purpose={}", toEmail, purpose);
        } catch (RestClientException e) {
            log.error("[ResendEmail] Gui OTP that bai: to={} purpose={} loi={}", toEmail, purpose, e.getMessage());
            throw new BadRequestException("Không thể gửi email, vui lòng thử lại sau");
        }
    }

    private String templateFor(OtpPurpose purpose) {
        return switch (purpose) {
            case REGISTER -> "email/otp-verify";
            case RESET_PASSWORD -> "email/password-reset";
            case RESET_PIN -> "email/pin-reset";
        };
    }

    private String subjectFor(OtpPurpose purpose) {
        return switch (purpose) {
            case REGISTER -> "Xác minh tài khoản Nino";
            case RESET_PASSWORD -> "Đặt lại mật khẩu Nino";
            case RESET_PIN -> "Đặt lại mã PIN Nino";
        };
    }
}
```

**If `RETURNS_DEEP_STUBS` proves unreliable** for `RestClient`'s generic fluent chain in Step 5's re-run (a known rough edge combining Mockito with heavily-generic fluent builders) — this is the one place in this task where the test as written might need adjustment. If so, the smallest fix is extracting the `resendRestClient.post()...toBodilessEntity()` chain into a single `protected` method (e.g. `protected void postToResend(ResendEmailRequest body)`) that the test can verify was *called* (via a partial mock / `@Spy`) rather than mocking the fluent chain's return values directly. Only make this change if the deep-stub approach genuinely fails to compile or behaves unpredictably — note which happened in your report.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=ResendEmailServiceTest`
Expected: PASS.

- [ ] **Step 8: Run the full suite and commit**

Run: `./mvnw -q test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/model/entity/OtpPurpose.java src/main/resources/templates/email/ src/main/java/com/app/nino/config/ResendConfig.java src/main/java/com/app/nino/service/ResendEmailService.java src/test/java/com/app/nino/service/ResendEmailServiceTest.java
git commit -m "feat: add ResendEmailService for OTP email delivery

Renders 1 of 3 Thymeleaf templates (registration/password-reset/
PIN-reset) with the OTP code and posts to Resend's API via RestClient.
Never logs the plaintext code. Not yet called from anywhere —
OtpService lands in the next commit.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 5: `OtpService`

**Files:**
- Create: `src/main/java/com/app/nino/model/dto/response/OtpVerifyResponse.java`
- Create: `src/main/java/com/app/nino/service/OtpService.java`
- Test: `src/test/java/com/app/nino/service/OtpServiceTest.java`

**Interfaces:**
- Consumes: `ResendEmailService.sendOtp(...)` (Task 4), `JwtTokenProvider.generateOtpToken(String, String)` (Task 3), `OtpPurpose` (Task 4), `TooManyRequestsException` (Task 1)
- Produces: `OtpService.requestOtp(String email, OtpPurpose purpose): void`, `OtpService.verifyOtp(String email, OtpPurpose purpose, String submittedOtp): OtpVerifyResponse`

- [ ] **Step 1: Create the response DTO**

Create `src/main/java/com/app/nino/model/dto/response/OtpVerifyResponse.java`:

```java
package com.app.nino.model.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OtpVerifyResponse {
    private boolean verified;
    /** Chi co gia tri khi purpose=REGISTER va verified=true. */
    private String accessToken;
    private String refreshToken;
    /** Chi co gia tri khi purpose=RESET_PASSWORD/RESET_PIN va verified=true. */
    private String resetToken;
}
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/com/app/nino/service/OtpServiceTest.java`:

```java
package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.exception.TooManyRequestsException;
import com.app.nino.model.dto.response.OtpVerifyResponse;
import com.app.nino.model.entity.OtpPurpose;
import com.app.nino.model.entity.Role;
import com.app.nino.model.entity.User;
import com.app.nino.repository.UserRepository;
import com.app.nino.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ResendEmailService resendEmailService;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private UserRepository userRepo;

    @InjectMocks
    private OtpService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "ttlMinutes", 5L);
        ReflectionTestUtils.setField(service, "rateLimitSeconds", 60L);
        ReflectionTestUtils.setField(service, "maxVerifyAttempts", 5);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── requestOtp ──────────────────────────────────────────────────────

    @Test
    void requestOtp_register_sendsEmailAndStoresHashedCode() {
        when(redisTemplate.hasKey("auth:otp:ratelimit:REGISTER:a@b.com")).thenReturn(false);
        when(userRepo.findByEmail("a@b.com")).thenReturn(Optional.of(User.builder().id(1L).email("a@b.com").build()));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.requestOtp("a@b.com", OtpPurpose.REGISTER);

        verify(valueOps).set(eq("auth:otp:code:REGISTER:a@b.com"), anyString(), eq(Duration.ofMinutes(5)));
        verify(valueOps).set(eq("auth:otp:ratelimit:REGISTER:a@b.com"), eq("1"), eq(Duration.ofSeconds(60)));
        verify(resendEmailService).sendOtp(eq("a@b.com"), anyString(), eq(OtpPurpose.REGISTER));
    }

    @Test
    void requestOtp_neverStoresOrEmailsThePlaintextCodeInTheSamePlace() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(userRepo.findByEmail("a@b.com")).thenReturn(Optional.of(User.builder().id(1L).email("a@b.com").build()));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ArgumentCaptor<String> emailedOtp = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> storedValue = ArgumentCaptor.forClass(String.class);

        service.requestOtp("a@b.com", OtpPurpose.REGISTER);

        verify(resendEmailService).sendOtp(anyString(), emailedOtp.capture(), any());
        verify(valueOps).set(eq("auth:otp:code:REGISTER:a@b.com"), storedValue.capture(), any());
        assertEquals(6, emailedOtp.getValue().length());
        assertNotEquals(emailedOtp.getValue(), storedValue.getValue());
        assertEquals(sha256(emailedOtp.getValue()), storedValue.getValue());
    }

    @Test
    void requestOtp_whenAlreadyRateLimited_throwsTooManyRequests() {
        when(redisTemplate.hasKey("auth:otp:ratelimit:REGISTER:a@b.com")).thenReturn(true);

        assertThrows(TooManyRequestsException.class, () -> service.requestOtp("a@b.com", OtpPurpose.REGISTER));
        verify(resendEmailService, never()).sendOtp(any(), any(), any());
    }

    @Test
    void requestOtp_registerWithUnknownEmail_throwsBadRequest() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(userRepo.findByEmail("nobody@b.com")).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> service.requestOtp("nobody@b.com", OtpPurpose.REGISTER));
        verify(resendEmailService, never()).sendOtp(any(), any(), any());
    }

    @Test
    void requestOtp_resetPasswordWithUnknownEmail_silentlySucceedsWithoutSendingEmail() {
        // Chong do tim tai khoan (account enumeration) qua luong quen mat
        // khau — khong duoc tiet lo email co ton tai hay khong.
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(userRepo.findByEmail("nobody@b.com")).thenReturn(Optional.empty());
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        assertDoesNotThrow(() -> service.requestOtp("nobody@b.com", OtpPurpose.RESET_PASSWORD));

        verify(resendEmailService, never()).sendOtp(any(), any(), any());
    }

    // ── verifyOtp ───────────────────────────────────────────────────────

    @Test
    void verifyOtp_correctCode_forRegister_activatesAccountAndReturnsLoginTokens() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("auth:otp:attempts:REGISTER:a@b.com")).thenReturn(1L);
        when(valueOps.get("auth:otp:code:REGISTER:a@b.com")).thenReturn(sha256("123456"));
        User user = User.builder().id(1L).email("a@b.com").roles(Set.of(Role.builder().name("ROLE_USER").build())).build();
        when(userRepo.findByEmail("a@b.com")).thenReturn(Optional.of(user));
        when(jwtTokenProvider.generateAccessToken(any(), any(), any())).thenReturn("access-tok");
        when(jwtTokenProvider.generateRefreshToken(any(), any())).thenReturn("refresh-tok");

        OtpVerifyResponse result = service.verifyOtp("a@b.com", OtpPurpose.REGISTER, "123456");

        assertTrue(result.isVerified());
        assertEquals("access-tok", result.getAccessToken());
        assertEquals("refresh-tok", result.getRefreshToken());
        assertEquals("ACT", user.getStatus());
        verify(userRepo).save(user);
        verify(redisTemplate).delete("auth:otp:code:REGISTER:a@b.com");
        verify(redisTemplate).delete("auth:otp:attempts:REGISTER:a@b.com");
    }

    @Test
    void verifyOtp_correctCode_forResetPassword_returnsResetTokenNotLoginTokens() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("auth:otp:attempts:RESET_PASSWORD:a@b.com")).thenReturn(1L);
        when(valueOps.get("auth:otp:code:RESET_PASSWORD:a@b.com")).thenReturn(sha256("654321"));
        when(jwtTokenProvider.generateOtpToken("a@b.com", "RESET_PASSWORD")).thenReturn("reset-tok");

        OtpVerifyResponse result = service.verifyOtp("a@b.com", OtpPurpose.RESET_PASSWORD, "654321");

        assertTrue(result.isVerified());
        assertEquals("reset-tok", result.getResetToken());
        assertNull(result.getAccessToken());
        verify(userRepo, never()).save(any());
        verify(userRepo, never()).findByEmail(any());
    }

    @Test
    void verifyOtp_wrongCode_returnsVerifiedFalse_andDoesNotDeleteKeys() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("auth:otp:attempts:REGISTER:a@b.com")).thenReturn(1L);
        when(valueOps.get("auth:otp:code:REGISTER:a@b.com")).thenReturn(sha256("123456"));

        OtpVerifyResponse result = service.verifyOtp("a@b.com", OtpPurpose.REGISTER, "000000");

        assertFalse(result.isVerified());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void verifyOtp_expiredOrNeverRequested_returnsVerifiedFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("auth:otp:attempts:REGISTER:a@b.com")).thenReturn(1L);
        when(valueOps.get("auth:otp:code:REGISTER:a@b.com")).thenReturn(null);

        OtpVerifyResponse result = service.verifyOtp("a@b.com", OtpPurpose.REGISTER, "123456");

        assertFalse(result.isVerified());
    }

    @Test
    void verifyOtp_exceedsMaxAttempts_throwsTooManyRequests() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("auth:otp:attempts:REGISTER:a@b.com")).thenReturn(6L);

        assertThrows(TooManyRequestsException.class,
            () -> service.verifyOtp("a@b.com", OtpPurpose.REGISTER, "000000"));
    }

    @Test
    void verifyOtp_firstAttempt_setsFiveMinuteTtlOnAttemptsKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("auth:otp:attempts:REGISTER:a@b.com")).thenReturn(1L);
        when(valueOps.get("auth:otp:code:REGISTER:a@b.com")).thenReturn(null);

        service.verifyOtp("a@b.com", OtpPurpose.REGISTER, "123456");

        verify(redisTemplate).expire("auth:otp:attempts:REGISTER:a@b.com", Duration.ofMinutes(5));
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q test -Dtest=OtpServiceTest`
Expected: FAIL to compile — `OtpService` doesn't exist yet.

- [ ] **Step 4: Implement `OtpService`**

Create `src/main/java/com/app/nino/service/OtpService.java`:

```java
package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.exception.TooManyRequestsException;
import com.app.nino.model.dto.response.OtpVerifyResponse;
import com.app.nino.model.entity.OtpPurpose;
import com.app.nino.model.entity.User;
import com.app.nino.model.entity.UserStatus;
import com.app.nino.repository.UserRepository;
import com.app.nino.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Gui va xac thuc ma OTP qua email (Resend) cho 3 muc dich: xac minh dang
 *  ky (REGISTER), quen mat khau (RESET_PASSWORD), quen PIN (RESET_PIN). OTP
 *  CHI luu hash SHA-256 trong Redis, KHONG BAO GIO plaintext — plaintext chi
 *  ton tai tam thoi trong bo nho giua luc sinh va luc goi ResendEmailService.
 *
 *  Fail-CLOSED (khac voi TokenBlacklistService fail-open): loi Redis o day
 *  phai la loi ro rang, khong duoc am tham coi nhu xac thuc dung. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final String KEY_PREFIX = "auth:otp:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final ResendEmailService resendEmailService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepo;

    @Value("${app.otp.ttl-minutes:5}")
    private long ttlMinutes;

    @Value("${app.otp.rate-limit-seconds:60}")
    private long rateLimitSeconds;

    @Value("${app.otp.max-verify-attempts:5}")
    private int maxVerifyAttempts;

    public void requestOtp(String email, OtpPurpose purpose) {
        String rateLimitKey = rateLimitKey(purpose, email);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(rateLimitKey))) {
            throw new TooManyRequestsException("Vui lòng đợi trước khi yêu cầu gửi lại mã");
        }

        Optional<User> user = userRepo.findByEmail(email);
        if (purpose == OtpPurpose.REGISTER && user.isEmpty()) {
            throw new BadRequestException("Email chưa đăng ký");
        }
        if (purpose != OtpPurpose.REGISTER && user.isEmpty()) {
            // RESET_PASSWORD/RESET_PIN voi email khong ton tai — khong tiet
            // lo qua response (chong do tim tai khoan), coi nhu da xu ly
            // xong ma khong thuc su gui gi.
            redisTemplate.opsForValue().set(rateLimitKey, "1", Duration.ofSeconds(rateLimitSeconds));
            log.info("[Otp] Yeu cau OTP cho email khong ton tai (bo qua, khong tiet lo): purpose={}", purpose);
            return;
        }

        String otpCode = generateOtp();
        redisTemplate.opsForValue().set(codeKey(purpose, email), hashOtp(otpCode), Duration.ofMinutes(ttlMinutes));
        redisTemplate.opsForValue().set(rateLimitKey, "1", Duration.ofSeconds(rateLimitSeconds));

        resendEmailService.sendOtp(email, otpCode, purpose);
        log.info("[Otp] Da gui OTP: email={} purpose={}", email, purpose);
    }

    public OtpVerifyResponse verifyOtp(String email, OtpPurpose purpose, String submittedOtp) {
        String attemptsKey = attemptsKey(purpose, email);
        Long attempts = redisTemplate.opsForValue().increment(attemptsKey);
        if (attempts != null && attempts == 1L) {
            // Chi dat TTL lan dau tao key — tranh moi lan thu lam troi han
            // cua so, giong pattern AuthRateLimitFilter.isOverLimit().
            redisTemplate.expire(attemptsKey, Duration.ofMinutes(5));
        }
        if (attempts != null && attempts > maxVerifyAttempts) {
            throw new TooManyRequestsException("Bạn đã nhập sai quá nhiều lần, vui lòng yêu cầu mã mới");
        }

        String codeKey = codeKey(purpose, email);
        String storedHash = redisTemplate.opsForValue().get(codeKey);
        if (storedHash == null) {
            return OtpVerifyResponse.builder().verified(false).build();
        }

        boolean matches = MessageDigest.isEqual(
            storedHash.getBytes(StandardCharsets.UTF_8),
            hashOtp(submittedOtp).getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            return OtpVerifyResponse.builder().verified(false).build();
        }

        redisTemplate.delete(codeKey);
        redisTemplate.delete(attemptsKey);

        return switch (purpose) {
            case REGISTER -> activateAndIssueTokens(email);
            case RESET_PASSWORD, RESET_PIN -> OtpVerifyResponse.builder()
                .verified(true)
                .resetToken(jwtTokenProvider.generateOtpToken(email, purpose.name()))
                .build();
        };
    }

    private OtpVerifyResponse activateAndIssueTokens(String email) {
        User user = userRepo.findByEmail(email)
            .orElseThrow(() -> new BadRequestException("Tài khoản không tồn tại"));
        user.setStatus(UserStatus.ACTIVE.getCode());
        userRepo.save(user);

        String sid = UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getRoles(), sid);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), sid);
        log.info("[Otp] Kich hoat tai khoan qua OTP: userId={} email={}", user.getId(), email);

        return OtpVerifyResponse.builder()
            .verified(true)
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .build();
    }

    private String generateOtp() {
        int code = SECURE_RANDOM.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    private String hashOtp(String otp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(otp.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 khong kha dung", e);
        }
    }

    private String rateLimitKey(OtpPurpose purpose, String email) {
        return KEY_PREFIX + "ratelimit:" + purpose + ":" + email;
    }

    private String codeKey(OtpPurpose purpose, String email) {
        return KEY_PREFIX + "code:" + purpose + ":" + email;
    }

    private String attemptsKey(OtpPurpose purpose, String email) {
        return KEY_PREFIX + "attempts:" + purpose + ":" + email;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=OtpServiceTest`
Expected: PASS.

- [ ] **Step 6: Run the full suite and commit**

Run: `./mvnw -q test`
Expected: all green.

```bash
git add src/main/java/com/app/nino/model/dto/response/OtpVerifyResponse.java src/main/java/com/app/nino/service/OtpService.java src/test/java/com/app/nino/service/OtpServiceTest.java
git commit -m "feat: add OtpService (request/verify OTP orchestration)

requestOtp() rate-limits per email+purpose, generates a SecureRandom
6-digit code, stores only its SHA-256 hash in Redis, and emails the
plaintext via ResendEmailService. verifyOtp() caps verify attempts,
compares hashes via MessageDigest.isEqual, and on success either
activates the account + issues login tokens (REGISTER) or issues a
short-lived reset token (RESET_PASSWORD/RESET_PIN) — never a direct
password/PIN change. RESET_PASSWORD/RESET_PIN silently no-op on an
unknown email to avoid account enumeration. Not yet reachable via any
endpoint — OtpController lands in the next commit.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 6: `OtpController`

**Files:**
- Create: `src/main/java/com/app/nino/model/dto/request/OtpRequestRequest.java`
- Create: `src/main/java/com/app/nino/model/dto/request/OtpVerifyRequest.java`
- Create: `src/main/java/com/app/nino/controller/OtpController.java`

**Interfaces:**
- Consumes: `OtpService.requestOtp(...)`/`verifyOtp(...)` (Task 5)
- Produces: `POST /api/v1/auth/otp/request`, `POST /api/v1/auth/otp/verify`

No test-first step — no controller-level (MockMvc) test exists anywhere in this codebase for any controller (`AuthController` included); only service-layer tests exist, and `OtpService` already has thorough coverage from Task 5. Verified by the full suite (including the app-context-boot test) staying green.

- [ ] **Step 1: Create the request DTOs**

Create `src/main/java/com/app/nino/model/dto/request/OtpRequestRequest.java`:

```java
package com.app.nino.model.dto.request;

import com.app.nino.model.entity.OtpPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OtpRequestRequest {

    @NotBlank(message = "email khong duoc de trong")
    @Email(message = "email khong hop le")
    private String email;

    @NotNull(message = "purpose khong duoc de trong")
    private OtpPurpose purpose;
}
```

Create `src/main/java/com/app/nino/model/dto/request/OtpVerifyRequest.java`:

```java
package com.app.nino.model.dto.request;

import com.app.nino.model.entity.OtpPurpose;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OtpVerifyRequest {

    @NotBlank(message = "email khong duoc de trong")
    @Email(message = "email khong hop le")
    private String email;

    @NotNull(message = "purpose khong duoc de trong")
    private OtpPurpose purpose;

    @NotBlank(message = "otp khong duoc de trong")
    private String otp;
}
```

- [ ] **Step 2: Create the controller**

Create `src/main/java/com/app/nino/controller/OtpController.java`:

```java
package com.app.nino.controller;

import com.app.nino.exception.BadRequestException;
import com.app.nino.model.dto.request.OtpRequestRequest;
import com.app.nino.model.dto.request.OtpVerifyRequest;
import com.app.nino.model.dto.response.BaseResponse;
import com.app.nino.model.dto.response.OtpVerifyResponse;
import com.app.nino.service.OtpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/otp")
@RequiredArgsConstructor
@Tag(name = "OTP", description = "Gửi và xác thực mã OTP qua email (đăng ký, quên mật khẩu, quên PIN)")
public class OtpController {

    private final OtpService otpService;

    @PostMapping("/request")
    @Operation(summary = "Gửi mã OTP qua email")
    public ResponseEntity<BaseResponse<?>> requestOtp(@Valid @RequestBody OtpRequestRequest req) {
        otpService.requestOtp(req.getEmail(), req.getPurpose());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(BaseResponse.success(null, "Đã gửi mã OTP, vui lòng kiểm tra email"));
    }

    @PostMapping("/verify")
    @Operation(summary = "Xác thực mã OTP")
    public ResponseEntity<BaseResponse<?>> verifyOtp(@Valid @RequestBody OtpVerifyRequest req) {
        OtpVerifyResponse result = otpService.verifyOtp(req.getEmail(), req.getPurpose(), req.getOtp());
        if (!result.isVerified()) {
            throw new BadRequestException("Mã OTP không đúng hoặc đã hết hạn");
        }
        return ResponseEntity.ok(BaseResponse.success(result));
    }
}
```

- [ ] **Step 3: Run the full suite**

Run: `./mvnw -q test`
Expected: all green (this verifies the whole feature wires together — `OtpController` → `OtpService` → `ResendEmailService`/`JwtTokenProvider`/`StringRedisTemplate` all resolve correctly at Spring context boot in `NinoApiApplicationTests`).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/app/nino/model/dto/request/OtpRequestRequest.java src/main/java/com/app/nino/model/dto/request/OtpVerifyRequest.java src/main/java/com/app/nino/controller/OtpController.java
git commit -m "feat: add OtpController — POST /auth/otp/request and /verify

Wires OtpService up to 2 public endpoints. /request returns 202 with
no OTP in the body; /verify translates a failed verification into the
existing BadRequestException convention (400) rather than a bespoke
success:false-with-200 shape. Completes the OTP email-verification
feature end to end (register → otp/request → otp/verify → activated +
logged in; forgot-password/PIN → otp/request → otp/verify → reset
token for a future, separate reset endpoint).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- `app.resend.*`/`app.otp.*` config, Thymeleaf dependency → Task 1. Covered.
- `TooManyRequestsException` → 429 → Task 1. Covered.
- `register()` requires OTP verification (status REG, no session tokens) → Task 2. Covered.
- `generateOtpToken` (5-min JWT, reuses sid/blacklist) → Task 3. Covered.
- `OtpPurpose` enum → Task 4. Covered.
- 3 email templates, inline styles, no flexbox/grid, "don't share" warning → Task 4. Covered.
- `ResendEmailService` (RestClient, never logs plaintext OTP) → Task 4. Covered.
- `OtpService.requestOtp`/`verifyOtp` (rate limit, SecureRandom, SHA-256 hash, `MessageDigest.isEqual`, attempt cap, activate-on-REGISTER, reset-token-on-RESET_*) → Task 5. Covered.
- `OtpController` (`/otp/request` 202 no-OTP-in-body, `/otp/verify`) → Task 6. Covered.
- Out-of-scope items from the spec (actual reset-password/reset-pin endpoints, mobile UI) — no tasks added for them, as intended.

**Placeholder scan:** every step has literal, complete code; no TBD/TODO. One explicit escape-hatch note in Task 4 Step 6 for the `RestClient` deep-stub mocking risk — flagged as a real implementation risk with a concrete fallback, not a placeholder.

**Type consistency:** `OtpPurpose` (Task 4) is the exact type referenced by `ResendEmailService.sendOtp` (Task 4), `OtpService.requestOtp`/`verifyOtp` (Task 5), and `OtpRequestRequest`/`OtpVerifyRequest` (Task 6) — same enum throughout, same package (`model.entity`). `JwtTokenProvider.generateOtpToken(String email, String purpose)` (Task 3) takes a `String` (not `OtpPurpose`) specifically to avoid a `security` → `model.entity`/`service` layering dependency — `OtpService` (Task 5) correctly calls it with `purpose.name()`. `OtpVerifyResponse` (Task 5) fields (`verified`/`accessToken`/`refreshToken`/`resetToken`) match exactly what `OtpController` (Task 6) reads. `StringRedisTemplate` is used directly (no new Spring `@Bean` needed — Spring Boot auto-configures it whenever a `RedisConnectionFactory` exists and no bean of that type already does, which is the case here since only a differently-typed `RedisTemplate<String,Object>` bean exists) — if Task 5's app-context test in Step 6 reveals this assumption is wrong, the fix is a 4-line `@Bean StringRedisTemplate` addition to `RedisConfig.java`, not a redesign.

**Scope check:** 6 tasks, each independently compilable-and-green at its end. Comparable in size to the precedent 10-task account-deletion plan, appropriately smaller since this feature has less cross-repo surface (backend-only, no mobile changes, no DB migration). No further decomposition needed.
