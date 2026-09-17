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
