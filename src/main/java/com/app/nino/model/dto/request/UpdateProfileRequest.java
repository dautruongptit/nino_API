package com.app.nino.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * PUT /users/me — cập nhật họ tên. Tách riêng khỏi RegisterRequest vì
 * request đó bắt buộc email + password (@NotBlank), khiến mọi lần đổi
 * tên đều bị 400 dù người dùng chỉ gửi fullName.
 */
@Data
public class UpdateProfileRequest {

    @NotBlank(message = "fullName khong duoc de trong")
    @Size(min = 2, max = 100)
    private String fullName;
}
