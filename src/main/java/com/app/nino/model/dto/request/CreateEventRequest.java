package com.app.nino.model.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
public class CreateEventRequest {

    @NotBlank(message = "title khong duoc de trong")
    private String title;

    @NotNull(message = "categoryId khong duoc de trong")
    private Long categoryId;

    @NotNull(message = "eventDate khong duoc de trong")
    private LocalDate eventDate;

    private LocalTime eventTime;

    /** NULL = sự kiện của bản thân */
    private Long relativeId;

    /** Người thân khác cùng tham gia (tùy chọn — xem event_participants) */
    private List<Long> participantIds;

    private Boolean isRecurring;

    /** YEARLY, MONTHLY, WEEKLY, LUNAR_YEARLY, DAILY, HOURLY, CUSTOM — bắt buộc nếu isRecurring=true */
    private String recurrenceType;

    /** Chỉ dùng khi recurrenceType = LUNAR_YEARLY */
    private Integer lunarDay;
    private Integer lunarMonth;

    /** Chỉ dùng khi recurrenceType = CUSTOM — VD: value=3, unit=WEEK -> mỗi 3 tuần */
    private Integer customIntervalValue;
    private String customIntervalUnit;

    private String notes;

    @Valid
    private List<ReminderRequest> reminders;
}
