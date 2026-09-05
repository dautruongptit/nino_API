package com.app.nino.model.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GroupSummaryResponse {
    private String groupType;
    private String displayName;
    private Long count;
}
