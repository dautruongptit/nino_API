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
