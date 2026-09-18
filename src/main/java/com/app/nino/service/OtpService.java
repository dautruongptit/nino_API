package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.exception.TooManyRequestsException;
import com.app.nino.model.dto.response.OtpVerifyResponse;
import com.app.nino.model.entity.OtpPurpose;
import com.app.nino.model.entity.User;
import com.app.nino.model.entity.UserStatus;
import com.app.nino.repository.UserRepository;
import com.app.nino.security.JwtTokenProvider;
import com.app.nino.security.TokenBlacklistService;
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
import java.util.Locale;
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
    private final TokenBlacklistService tokenBlacklistService;

    @Value("${app.otp.ttl-minutes:5}")
    private long ttlMinutes;

    @Value("${app.otp.rate-limit-seconds:60}")
    private long rateLimitSeconds;

    @Value("${app.otp.max-verify-attempts:5}")
    private int maxVerifyAttempts;

    public void requestOtp(String email, OtpPurpose purpose) {
        email = email.trim().toLowerCase(Locale.ROOT);

        String rateLimitKey = rateLimitKey(purpose, email);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(rateLimitKey))) {
            throw new TooManyRequestsException("Vui lòng đợi trước khi yêu cầu gửi lại mã");
        }

        Optional<User> user = userRepo.findByEmail(email);
        if (purpose == OtpPurpose.REGISTER && user.isEmpty()) {
            throw new BadRequestException("Email chưa đăng ký");
        }
        boolean registerAlreadyPastRegStatus = purpose == OtpPurpose.REGISTER
            && !UserStatus.REGISTERED.getCode().equals(user.get().getStatus());
        if ((purpose != OtpPurpose.REGISTER && user.isEmpty()) || registerAlreadyPastRegStatus) {
            // RESET_PASSWORD/RESET_PIN voi email khong ton tai, HOAC REGISTER voi
            // tai khoan da qua trang thai REG (da active/bi khoa/da xoa/...) — khong
            // tiet lo qua response, coi nhu da xu ly xong ma khong thuc su gui gi.
            redisTemplate.opsForValue().set(rateLimitKey, "1", Duration.ofSeconds(rateLimitSeconds));
            log.info("[Otp] Yeu cau OTP khong du dieu kien gui (bo qua, khong tiet lo): purpose={}", purpose);
            return;
        }

        String otpCode = generateOtp();
        redisTemplate.opsForValue().set(codeKey(purpose, email), hashOtp(otpCode), Duration.ofMinutes(ttlMinutes));
        redisTemplate.opsForValue().set(rateLimitKey, "1", Duration.ofSeconds(rateLimitSeconds));
        // Xoa dem so lan thu sai cu — neu khong, mot yeu cau OTP moi sau khi
        // da cham tran verify se van bi TooManyRequestsException ngay lan
        // thu dau tien voi ma MOI (TTL 5 phut cua key attempts cu van con
        // hieu luc), du loi khuyen cua chinh he thong la "yeu cau ma moi".
        redisTemplate.delete(attemptsKey(purpose, email));

        resendEmailService.sendOtp(email, otpCode, purpose);
        log.info("[Otp] Da gui OTP: email={} purpose={}", email, purpose);
    }

    public OtpVerifyResponse verifyOtp(String email, OtpPurpose purpose, String submittedOtp) {
        email = email.trim().toLowerCase(Locale.ROOT);

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
        if (!UserStatus.REGISTERED.getCode().equals(user.getStatus())) {
            // Chi kich hoat tai khoan dang o trang thai cho xac minh (REG).
            // Neu khong kiem tra, mot tai khoan da bi BAN/LCK/DEL boi admin
            // van co the bi "hoi sinh" ve ACT + cap token dang nhap that chi
            // bang cach xin lai OTP REGISTER qua email cu (bypass kiem duyet).
            throw new BadRequestException("Tài khoản đã được xác minh hoặc không hợp lệ");
        }
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

    /** Xac nhan mot lan reset PIN sau khi da verify OTP thanh cong (purpose
     *  RESET_PIN). KHONG co gi de "reset" o server (PIN 100% local, xem
     *  AppLockService ben mobile) — day chi la buoc audit + lam resetToken
     *  khong the dung lai lan thu 2. */
    public void confirmPinReset(String resetToken) {
        if (!jwtTokenProvider.validateToken(resetToken)) {
            throw new BadRequestException("Token không hợp lệ hoặc đã hết hạn");
        }
        boolean isPinResetToken = "otp_reset".equals(jwtTokenProvider.getTokenType(resetToken))
            && OtpPurpose.RESET_PIN.name().equals(jwtTokenProvider.getOtpPurpose(resetToken));
        if (!isPinResetToken) {
            throw new BadRequestException("Token không hợp lệ hoặc đã hết hạn");
        }

        String email = jwtTokenProvider.getOtpEmail(resetToken);
        User user = userRepo.findByEmail(email)
            .orElseThrow(() -> new BadRequestException("Tài khoản không tồn tại"));

        log.info("[Otp] Xac nhan reset PIN qua email: userId={} email={}", user.getId(), email);
        tokenBlacklistService.blacklist(
            jwtTokenProvider.getSid(resetToken),
            jwtTokenProvider.getRemainingValidity(resetToken));
    }
}
