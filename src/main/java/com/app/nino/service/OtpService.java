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
