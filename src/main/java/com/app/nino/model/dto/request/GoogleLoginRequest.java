package com.app.nino.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleLoginRequest {

    @NotBlank(message = "idToken khong duoc de trong")
    private String idToken;

    /** Tuy chon — VD "Pixel 8", "iPhone 15 Pro" — hien thi trong lich su dang nhap. */
    private String deviceName;
}
