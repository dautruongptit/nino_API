package com.app.nino.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateCategoryRequest {

    @NotBlank(message = "displayName khong duoc de trong")
    @Size(max = 50, message = "displayName toi da 50 ky tu")
    private String displayName;

    @NotBlank(message = "icon khong duoc de trong")
    @Size(max = 50, message = "icon toi da 50 ky tu")
    private String icon;  // emoji hoac ten icon (VD "🎂" hoac "cake")

    @NotBlank(message = "colorHex khong duoc de trong")
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "colorHex phai co dang #RRGGBB")
    private String colorHex;
}
