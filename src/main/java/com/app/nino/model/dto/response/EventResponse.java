package com.app.nino.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class EventResponse {
    private Long id;
    private String title;
    private Long categoryId;
    private String categoryName;
    private String categoryIcon;
    private String categoryColor;
    private java.time.LocalDate eventDate;
    private java.time.LocalTime eventTime;
    private Boolean isRecurring;
    private String recurrenceType;
    private Integer lunarDay;
    private Integer lunarMonth;
    private Integer customIntervalValue;
    private String customIntervalUnit;
    private String notes;
    // Thông tin người thân (null nếu là sự kiện bản thân)
    private Long relativeId;
    private String relativeName;
    private String relativeGroupType;
    // Countdown
    private Long daysUntil;  // âm = đã qua, 0 = hôm nay, dương = còn x ngày
    // Danh sách reminder config
    private List<ReminderResponse> reminders;

        private List<ParticipantSummary> participants;

    @Data
    @Builder
    public static class ParticipantSummary {
        private Long id;
      private String name;
       private String avatarUrl;
   }
}
