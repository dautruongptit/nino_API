package com.app.nino.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CreateRelativeRequest {

    @NotBlank(message = "name khong duoc de trong")
    private String name;

    private String nickname;

    @NotBlank(message = "groupType khong duoc de trong")
    private String groupType;   // GIA_DINH, VO_CHONG, CON_CAI, BAN_BE, ANH_CHI_EM

    private String gender;      // MALE, FEMALE, OTHER

    private LocalDate dateOfBirth;
    private String location;
    private BigDecimal heightCm;
    private BigDecimal weightKg;

    /** Mảng string, VD: ["đọc sách","nấu ăn"] — Service tự serialize sang JSON string khi lưu DB */
    private java.util.List<String> hobbies;

    private String notes;

    private String avatarUrl;
}
