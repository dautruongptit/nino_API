package com.app.nino.model.dto.response;

import com.app.nino.model.entity.User;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserProfileResponse {

    private Long id;
    private String username;
    private String fullName;
    private String email;
    private String status;
    private String avatarUrl;
    private String language;
    private Boolean darkMode;
    private Integer totalEvents;
    private Integer totalRelatives;
    private Boolean isGoogleLinked;
    private Boolean googleCalendarConnected;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;

    public static UserProfileResponse from(User u) {
        return UserProfileResponse.builder()
            .id(u.getId())
            .username(u.getUsername())
            .fullName(u.getFullName())
            .email(u.getEmail())
            .status(u.getStatus())
            .avatarUrl(u.getAvatarUrl())
            .language(u.getLanguage())
            .darkMode(u.getDarkMode())
            .totalEvents(u.getTotalEvents())
            .totalRelatives(u.getTotalRelatives())
            .isGoogleLinked(u.getGoogleId() != null)
            .googleCalendarConnected(u.getGoogleCalendarToken() != null)
            .lastLoginAt(u.getLastLoginAt())
            .createdAt(u.getCreatedAt())
            .build();
    }
}
