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
