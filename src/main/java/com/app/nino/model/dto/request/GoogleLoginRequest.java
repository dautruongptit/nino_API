package com.app.nino.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GoogleLoginRequest {

    @NotBlank(message = "idToken khong duoc de trong")
    private String idToken;

    /** Tuy chon — VD "Pixel 8", "iPhone 15 Pro" — hien thi trong lich su dang nhap. */
    @Size(max = 100, message = "deviceName toi da 100 ky tu")
    private String deviceName;
}
