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
        // NOTE: mocking RestClient's fluent chain directly via RETURNS_DEEP_STUBS
        // (`when(resendRestClient.post().uri(...).body(...).retrieve().toBodilessEntity())
        // .thenThrow(...)`) proved unreliable here — a known rough edge combining
        // Mockito deep stubs with RestClient's heavily-generic fluent builder. The
        // stub silently didn't apply, so the exception was never thrown. Falling back
        // to the approach the task brief calls out explicitly: extract the RestClient
        // call into a protected `postToResend` method and verify via a partial
        // mock (spy) instead of mocking the fluent chain's return values.
        when(templateEngine.process(anyString(), any(Context.class))).thenReturn("<html></html>");
        ResendEmailService spyService = spy(service);
        doThrow(new RestClientException("boom")).when(spyService).postToResend(any());

        assertThrows(BadRequestException.class,
            () -> spyService.sendOtp("user@example.com", "111111", OtpPurpose.REGISTER));
    }
}
