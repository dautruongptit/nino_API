package com.app.nino.model.dto.response;

import com.app.nino.model.entity.EventCategory;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EventCategoryResponse {

    private Long id;
    private String code;
    private String displayName;
    private String icon;
    private String colorHex;
    private Boolean isSystem;

    public static EventCategoryResponse from(EventCategory c) {
        return EventCategoryResponse.builder()
            .id(c.getId())
            .code(c.getCode())
            .displayName(c.getDisplayName())
            .icon(c.getIcon())
            .colorHex(c.getColorHex())
            .isSystem(c.getIsSystem())
            .build();
    }
}
