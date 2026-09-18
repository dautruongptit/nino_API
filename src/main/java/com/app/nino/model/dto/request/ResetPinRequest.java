package com.app.nino.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ResetPinRequest {

    @NotBlank(message = "resetToken khong duoc de trong")
    private String resetToken;
}
