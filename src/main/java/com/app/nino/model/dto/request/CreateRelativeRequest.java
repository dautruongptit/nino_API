package com.app.nino.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateRelativeRequest {

    @NotBlank(message = "name khong duoc de trong")
    private String name;

    private String nickname;

    @NotBlank(message = "groupType khong duoc de trong")
    private String groupType;   // GIA_DINH, VO_CHONG, CON_CAI, BAN_BE, ANH_CHI_EM

    private String gender;      // MALE, FEMALE, OTHER

    // Ngày sinh tách 3 phần — xem Relative.birthMonth/birthDay/birthYear.
    // birthYear null = không rõ năm sinh (không dùng giá trị đại diện nào).
    private Integer birthMonth;
    private Integer birthDay;
    private Integer birthYear;
    private String location;
    private BigDecimal heightCm;
    private BigDecimal weightKg;

    /** Mảng string, VD: ["đọc sách","nấu ăn"] — Service tự serialize sang JSON string khi lưu DB */
    private java.util.List<String> hobbies;

    private String notes;

    private String avatarUrl;
}
