package com.app.nino.model.dto.request;

import lombok.Data;

@Data
public class ReminderRequest {

    /** Chọn 1 trong 3 — không dùng đồng thời nhiều hơn 1 */
    private Integer remindDaysBefore;
    private Integer remindHoursBefore;
    private Integer remindMinutesBefore;

    /** Tuỳ chọn: sau lần bắn đầu tiên, tự bắn lại mỗi N phút cho tới khi
     * người dùng đọc thông báo (VD: nhắc uống thuốc mỗi 30 phút). */
    private Integer repeatIntervalMinutes;

    private Boolean isEnabled;
}
