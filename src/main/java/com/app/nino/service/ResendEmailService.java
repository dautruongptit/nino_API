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

    record ResendEmailRequest(String from, List<String> to, String subject, String html) {}

    public void sendOtp(String toEmail, String otpCode, OtpPurpose purpose) {
        Context ctx = new Context();
        ctx.setVariable("otpCode", otpCode);
        String html = templateEngine.process(templateFor(purpose), ctx);

        ResendEmailRequest body = new ResendEmailRequest(fromAddress, List.of(toEmail), subjectFor(purpose), html);

        try {
            postToResend(body);
            log.info("[ResendEmail] Da gui OTP: to={} purpose={}", toEmail, purpose);
        } catch (RestClientException e) {
            log.error("[ResendEmail] Gui OTP that bai: to={} purpose={} loi={}", toEmail, purpose, e.getMessage());
            throw new BadRequestException("Không thể gửi email, vui lòng thử lại sau");
        }
    }

    /** Tach rieng loi goi RestClient de test co the mock/spy hanh vi nay
     *  doc lap voi chuoi fluent API generic cua RestClient. */
    protected void postToResend(ResendEmailRequest body) {
        resendRestClient.post()
            .uri("/emails")
            .body(body)
            .retrieve()
            .toBodilessEntity();
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
