package com.app.nino.model.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "email khong duoc de trong")
    @Email(message = "email khong hop le")
    private String email;

    @NotBlank(message = "password khong duoc de trong")
    private String password;

    /** Tuy chon — VD "Pixel 8", "iPhone 15 Pro" — hien thi trong lich su dang nhap. */
    @Size(max = 100, message = "deviceName toi da 100 ky tu")
    private String deviceName;
}
