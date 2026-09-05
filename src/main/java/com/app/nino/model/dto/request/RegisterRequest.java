package com.app.nino.model.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "fullName khong duoc de trong")
    @Size(min = 2, max = 100)
    private String fullName;

    @NotBlank(message = "email khong duoc de trong")
    @Email(message = "email khong hop le")
    private String email;

    @NotBlank(message = "password khong duoc de trong")
    @Size(min = 8, message = "password toi thieu 8 ky tu")
    private String password;
}
