package com.app.nino.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class HomeResponse {
    private String userName;
    private String avatarUrl;
    private Boolean googleCalendarConnected;
    private List<EventResponse> upcomingEvents;
    private List<EventResponse> myEvents;
    private List<RelativeResponse> relatives;
}
