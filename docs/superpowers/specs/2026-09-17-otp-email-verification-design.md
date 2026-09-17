# OTP Email Verification (Resend) — Design Spec

**Date:** 2026-09-17

**Goal:** Add a generic OTP send/verify flow, delivered via Resend email, reusable for three purposes: account-registration verification, password reset, and PIN reset. Wire the REGISTER purpose into the existing `register()` flow so new accounts require email verification before they can log in (a real behavior change — today `register()` activates accounts immediately). RESET_PASSWORD/RESET_PIN purposes stop at issuing a short-lived JWT the caller can use for a follow-up reset endpoint — that endpoint is a separate, future task.

**Out of scope:** the actual `/auth/reset-password` and `/auth/reset-pin` endpoints that consume the temp JWT; any mobile UI for these flows.

---

## Why this shape

The request came with a fully-specified design (config keys, class names, method signatures). Codebase research (2026-09-17) found several of its assumptions don't match the current code, so this spec reconciles the two:

| Spec assumed | Actually | Resolution |
|---|---|---|
| `StringRedisTemplate` already configured | Only `RedisTemplate<String,Object>` (Jackson-wrapped) exists, used by `TokenBlacklistService` | Rely on Spring Boot's own `StringRedisTemplate` auto-configuration (present by default once a `RedisConnectionFactory` bean exists and no conflicting bean overrides it) — verify at implementation time; add an explicit `@Bean` only if it's missing. |
| `com.app.nino.email` / `com.app.nino.otp` packages | Codebase is flat (`service/`, `controller/`, `exception/`, ...) — even single-concern integrations like FCM and Google auth live as `service/FcmService.java`, `service/GoogleAuthService.java`, no dedicated packages | `service/ResendEmailService.java`, `service/OtpService.java`, `controller/OtpController.java` (own class, same flat package as `DeviceController`/`NotificationController`). |
| Flyway at V10 | Actually V35 | Not relevant — this feature needs **no migration** (OTP lives only in Redis; `status` values `REG`/`VRF` already exist in `UserStatus`, just unused so far). |
| Generic JWT claims+TTL method exists | `JwtTokenProvider` only has purpose-built `generateAccessToken`/`generateRefreshToken`, each with hardcoded expiry | Add `generateOtpToken(String email, OtpPurpose purpose)` following the same `Jwts.builder()` pattern, own 5-minute expiry, own `jti` claim for blacklist-ability. |
| "test theo cách project đang test hiện tại" via embedded Redis/Testcontainers | No embedded-Redis/Testcontainers dependency exists; **every** Redis-touching test in this codebase (`TokenBlacklistServiceTest`) is pure Mockito mocking | Match that: mock `StringRedisTemplate`/`ValueOperations` with Mockito, no new test infrastructure. |

---

## Config (`application.yml`)

Nested under the existing `app:` block (matches `app.jwt.*`, `app.firebase.*`, `app.google.*` convention) rather than top-level `resend:`/`otp:`:

```yaml
app:
  resend:
    api-key: ${RESEND_API_KEY}
    from: "Nino <no-reply@nino.thongtinchinhhieu.site>"
  otp:
    ttl-minutes: ${OTP_TTL_MINUTES:5}
    rate-limit-seconds: ${OTP_RATE_LIMIT_SECONDS:60}
    max-verify-attempts: ${OTP_MAX_VERIFY_ATTEMPTS:5}
    reset-token-ttl-minutes: ${OTP_RESET_TOKEN_TTL_MINUTES:5}
```

`app.resend.api-key` has **no default** — required secret, fail-fast on missing config, same pattern as `JWT_SECRET`/`DB_PASSWORD`. Everything else is optional with sane defaults, matching the spec's stated values.

## `pom.xml`

Add `spring-boot-starter-thymeleaf` (needed for the 3 HTML email templates; not currently a dependency). `RestClient` (for calling Resend's HTTP API) needs no new dependency — it ships with `spring-boot-starter-web`, already present.

## `ResendEmailService` (`service/ResendEmailService.java`)

- Injects a `RestClient` (new `@Bean` in a small `config/ResendConfig.java`, base URL `https://api.resend.com`) and a `TemplateEngine` (Thymeleaf, auto-configured once the starter is added).
- `sendOtp(String toEmail, String otpCode, OtpPurpose purpose)`: picks the template (`otp-verify.html` for REGISTER, `password-reset.html` for RESET_PASSWORD, `pin-reset.html` for RESET_PIN) and subject line per purpose, renders it with `otpCode` as a Thymeleaf context variable, `POST`s to `/emails` with `Authorization: Bearer <api-key>`, body `{from, to, subject, html}`.
- **Never logs `otpCode`** — not even at DEBUG. Logs are limited to `email` (or a masked form) and `purpose`, matching `AuthService`'s existing `log.info("[Auth] ...")` style.
- Resend API failures throw a `BadRequestException` (or propagate as 500 via the catch-all handler) — no silent swallow, since a failed send with a "your code was sent" response to the client would be misleading. `OtpService.requestOtp` calls this synchronously, so a Resend outage surfaces as a request failure rather than a silently-lost OTP.

## Email templates (`src/main/resources/templates/email/`)

`otp-verify.html`, `password-reset.html`, `pin-reset.html` — Thymeleaf, all inline `style="..."` attributes (no `<style>` blocks, no flexbox/grid — table-based layout for email-client compatibility), each renders `${otpCode}` and the line "Không chia sẻ mã này với bất kỳ ai, kể cả nhân viên hỗ trợ." Purpose-specific copy otherwise (registration welcome vs. password-reset vs. PIN-reset framing).

## `OtpService` (`service/OtpService.java`)

```java
public enum OtpPurpose { REGISTER, RESET_PASSWORD, RESET_PIN }
```

Redis key family (matches `TokenBlacklistService`'s `auth:blacklist:` prefix convention):
- `auth:otp:ratelimit:{purpose}:{email}` — existence-only guard, TTL = `app.otp.rate-limit-seconds`
- `auth:otp:code:{purpose}:{email}` — SHA-256 hex hash of the OTP, TTL = `app.otp.ttl-minutes`
- `auth:otp:attempts:{purpose}:{email}` — verify-attempt counter, TTL = 5 minutes fixed (independent of `ttl-minutes`, so attempts don't outlive a re-requested OTP incorrectly — see Self-Review)

`requestOtp(String email, OtpPurpose purpose)`:
1. If the rate-limit key exists → `TooManyRequestsException`.
2. Generate 6-digit code via `SecureRandom` (never `java.util.Random`).
3. Hash with SHA-256 (hex-encoded), store at the code key with its TTL.
4. Set the rate-limit key (value irrelevant, e.g. `"1"`) with its TTL.
5. Call `ResendEmailService.sendOtp(email, plainCode, purpose)` — the **only** place the plaintext code exists outside the user's inbox.

**Fail-closed**, unlike `TokenBlacklistService`: any Redis exception here propagates (no catch-and-continue) — an OTP request that silently "succeeds" without actually being retrievable for verification would be worse than a visible 500.

`verifyOtp(String email, OtpPurpose purpose, String submittedOtp)`:
1. Increment the attempts key (`INCR`, set TTL only on first increment). If count `>` `app.otp.max-verify-attempts` → `TooManyRequestsException`.
2. Read the code key. If absent (expired/never requested) → return `false`.
3. Hash `submittedOtp` the same way, compare via `MessageDigest.isEqual(...)` (constant-time), never `String.equals`/`==`.
4. On match: delete both the code key and the attempts key, then:
   - `REGISTER`: set the user's `status` to `UserStatus.ACTIVE.getCode()` (`"ACT"`), return a result carrying freshly-generated access+refresh tokens (reusing `AuthService`'s existing token-issuing path — exact integration point decided at plan time, likely `OtpService` calling back into `AuthService`/`JwtTokenProvider` rather than duplicating login logic).
   - `RESET_PASSWORD` / `RESET_PIN`: return a result carrying a temp JWT from `JwtTokenProvider.generateOtpToken(email, purpose)` (5-minute expiry, own `jti`).
5. On mismatch: return `false` (leave the code key alone — the user gets more tries up to the attempt cap, doesn't need to re-request).

## `JwtTokenProvider` addition

New method, same `Jwts.builder()...signWith(getSigningKey()).compact()` pattern as `generateAccessToken`/`generateRefreshToken`:

```java
public String generateOtpToken(String email, OtpPurpose purpose)
```

Claims: `email`, `purpose`, `type="otp_reset"`, `jti` (random UUID, for blacklist-ability by a future reset endpoint), expiry = `app.otp.reset-token-ttl-minutes`. A corresponding `validateOtpToken`/`getOtpClaims`-style accessor is added for the (future, out-of-scope) reset endpoint to consume — this task only needs to *produce* a valid, well-formed token, not parse it back.

## `TooManyRequestsException` (`exception/TooManyRequestsException.java`)

Flat, `extends RuntimeException`, single `(String message)` constructor — matches `BadRequestException`'s shape exactly. `GlobalExceptionHandler` gets one more `@ExceptionHandler` mapping it to HTTP 429, error code `"TOO_MANY_REQUESTS"`, same `BaseResponse.error(...)` shape as the existing handlers.

## `OtpController` (`controller/OtpController.java`)

`@RequestMapping("/auth/otp")` (nests under the existing `/auth` prefix; effective path `/api/v1/auth/otp/...`), same annotation/style conventions as `AuthController` (`@RequiredArgsConstructor`, `@Operation` Vietnamese summaries, `BaseResponse.success(...)`).

- `POST /request` — body `{email, purpose}` → `otpService.requestOtp(...)`, returns `202 Accepted`, empty/ack payload only (never the OTP).
- `POST /verify` — body `{email, purpose, otp}` → `otpService.verifyOtp(...)`.
  - `false` → `BadRequestException("Mã OTP không đúng hoặc đã hết hạn")` (controller translates the boolean into the existing error-response convention rather than a bespoke 200-with-`success:false` body).
  - `true` → `200` with either login tokens (REGISTER) or the temp JWT (RESET_PASSWORD/RESET_PIN), per `OtpService`'s result.

## `AuthService.register()` change

Today: `status("ACT")` — accounts are immediately loginable, no verification step. This spec changes that to `status(UserStatus.REGISTERED.getCode())` ("REG") — `User.canLogin()` already excludes `"REG"`, so newly-registered accounts simply can't log in until OTP-verified. `register()` does **not** itself trigger an OTP send — the client calls `POST /auth/otp/request` (purpose=REGISTER) as a separate, explicit step after registering, matching the two-endpoint contract given in the request. This is a deliberate, real behavior change to existing signup UX (confirmed with the requester) — flagged here so a reviewer doesn't mistake it for scope creep.

---

## Safety / design decisions worth calling out

- **OTP never stored or logged in plaintext anywhere** — only its SHA-256 hash touches Redis; the plaintext exists transiently in memory between generation and the `sendOtp` call, then only in the user's inbox.
- **Constant-time comparison** (`MessageDigest.isEqual`) prevents timing-based OTP guessing.
- **Rate limit + attempt cap are independent controls**: rate limit (`60s` default) throttles how often a *new* OTP can be requested; attempt cap (`5` default) throttles how many *guesses* are allowed against one already-issued OTP. Both are needed — rate limiting alone doesn't stop someone from spamming `/verify` against a single still-valid code.
- **Fail-closed** for `OtpService` (unlike the existing fail-open `TokenBlacklistService`) — a Redis outage during OTP request/verify must be a visible error, not a silent bypass of the verification step.
- **REGISTER activation is the only purpose that changes DB state** (`status` flip) — RESET_PASSWORD/RESET_PIN only ever hand back a capability token; they never touch the password/PIN directly, by design (the requester's explicit safety requirement — "không cho đổi ngay trong cùng request verify").

## Testing

- **`OtpServiceTest`**: mock `StringRedisTemplate`/`ValueOperations`, `ResendEmailService`, `JwtTokenProvider` (and whatever `AuthService`/token-issuing dependency REGISTER's success path ends up needing — decided at plan time). Cases: correct OTP → success path per purpose; wrong OTP → `false`, attempts incremented; expired/absent OTP → `false`; rate limit exceeded on request → `TooManyRequestsException`; verify-attempt cap exceeded → `TooManyRequestsException`; successful verify deletes both Redis keys (verify via `verify(redisTemplate)...delete(...)` calls, not just return value). Matches `TokenBlacklistServiceTest`'s existing Mockito-only pattern — no embedded Redis/Testcontainers added.
- **`ResendEmailServiceTest`**: verify the correct template is selected per `OtpPurpose`, the rendered HTML contains the OTP code and the "don't share" warning line, and the outbound request carries the right URL/headers/body shape. `RestClient`'s fluent builder is awkward to mock directly with plain Mockito — the plan should pick a concrete approach (e.g. `Mockito.mock(RestClient.class, RETURNS_DEEP_STUBS)`, or extracting the request-building into a separately-testable method) rather than leaving this to the implementer's improvisation.
- **`AuthServiceTest`**: extend the existing `register()` tests to assert the new status (`"REG"` not `"ACT"`) and that no tokens are returned/account isn't immediately loginable.
- **`GlobalExceptionHandlerTest`** (if one exists — verify at plan time) or an integration-style check: `TooManyRequestsException` → 429.
- **Schema validation**: none needed (no migration), but the full `./mvnw -q test` run (including `NinoApiApplicationTests`) still verifies the new `StringRedisTemplate`/`TemplateEngine`/`RestClient` beans wire up cleanly at context-boot.

## Self-review notes

- Attempts-key TTL is fixed at 5 minutes independent of `app.otp.ttl-minutes` so that requesting a fresh OTP (which resets the code key's TTL) doesn't also silently reset the attempt counter early, and so an attempts key doesn't outlive a long-configured OTP TTL indefinitely. If `app.otp.ttl-minutes` is ever configured above 5, this asymmetry should be revisited — noted for the plan/implementer rather than silently resolved either way.
- REGISTER's token-issuing on successful verify needs a concrete integration decision at plan time: either `OtpService` depends on `AuthService` (risk: circular dependency, since `AuthService.register()` doesn't currently depend on `OtpService` — but if `register()` is only changed to set a status string, no cycle exists) or a shared token-issuing helper is extracted. The plan must pick one and state it explicitly, not leave it implicit.
