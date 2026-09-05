package com.app.nino.model.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Getter
@RequiredArgsConstructor
public enum UserStatus {

    GUEST      ("GST", "Khach vang lai"),
    REGISTERED ("REG", "Da dang ky, chua xac minh"),
    VERIFIED   ("VRF", "Da xac minh email/phone"),
    ACTIVE     ("ACT", "Hoat dong binh thuong"),
    INACTIVE   ("INA", "Khong hoat dong"),
    LOCKED     ("LCK", "Bi khoa tam thoi"),
    BANNED     ("BAN", "Bi cam vinh vien"),
    DELETED    ("DEL", "Da xoa mem");

    private final String code;
    private final String description;

    /** Cache tra cuu O(1): code → enum. */
    private static final Map<String, UserStatus> CODE_MAP =
            Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(
                            UserStatus::getCode, Function.identity()));

    /**
     * Tra ve enum tu ma 3 ky tu.
     * @throws IllegalArgumentException neu code khong hop le.
     */
    public static UserStatus fromCode(String code) {
        return Optional.ofNullable(CODE_MAP.get(code))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown UserStatus code: " + code));
    }
}
