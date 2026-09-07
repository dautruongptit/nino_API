package com.app.nino.model.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReminderResponse {
    private Long id;
    private Integer remindDaysBefore;
    private Integer remindHoursBefore;
    private Integer remindMinutesBefore;
    private Integer repeatIntervalMinutes;
    private Boolean isEnabled;
}
