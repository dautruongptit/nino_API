package com.app.nino.model.dto.response;

import com.app.nino.model.entity.Notification;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class NotificationResponse {

    private Long id;
    private String title;
    private String body;
    private Boolean isRead;
    private Long eventId;
    private String eventTitle;
    private LocalDateTime sentAt;

    public static NotificationResponse from(Notification n) {
        return NotificationResponse.builder()
            .id(n.getId())
            .title(n.getTitle())
            .body(n.getBody())
            .isRead(n.getIsRead())
            .eventId(n.getEvent() != null ? n.getEvent().getId() : null)
            .eventTitle(n.getEvent() != null ? n.getEvent().getTitle() : null)
            .sentAt(n.getSentAt())
            .build();
    }
}
