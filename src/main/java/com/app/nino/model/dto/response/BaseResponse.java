package com.app.nino.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BaseResponse<T> {

    private final boolean success;
    private final T data;
    private final String message;
    private final String errorCode;

    @Builder.Default
    private final LocalDateTime timestamp = LocalDateTime.now();

    public static <T> BaseResponse<T> success(T data) {
        return BaseResponse.<T>builder()
            .success(true)
            .data(data)
            .build();
    }

    public static <T> BaseResponse<T> success(T data, String message) {
        return BaseResponse.<T>builder()
            .success(true)
            .data(data)
            .message(message)
            .build();
    }

    public static <T> BaseResponse<T> error(String message) {
        return BaseResponse.<T>builder()
            .success(false)
            .message(message)
            .build();
    }

    public static <T> BaseResponse<T> error(String message, String errorCode) {
        return BaseResponse.<T>builder()
            .success(false)
            .message(message)
            .errorCode(errorCode)
            .build();
    }
}
