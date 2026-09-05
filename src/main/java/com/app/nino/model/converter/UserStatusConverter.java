package com.app.nino.model.converter;
import com.app.nino.model.entity.UserStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)   // tự động áp dụng cho mọi field UserStatus
public class UserStatusConverter
        implements AttributeConverter<UserStatus, String> {

    @Override
    public String convertToDatabaseColumn(UserStatus status) {
        if (status == null) return null;
        return status.getCode();   // lưu "REG", "ACT", "LCK"... (3 ký tự)
    }

    @Override
    public UserStatus convertToEntityAttribute(String code) {
        if (code == null) return null;
        return UserStatus.fromCode(code);  // đọc lại từ "REG" → REGISTERED
    }
}