package com.app.nino.model.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LunarDateResponse {

    // ── Ngày dương lịch gốc ──
    private Integer solarDay;
    private Integer solarMonth;
    private Integer solarYear;

    // ── Ngày âm lịch tương ứng ──
    private Integer lunarDay;
    private Integer lunarMonth;
    private Integer lunarYear;
    private Boolean isLeapMonth;      // true nếu rơi vào tháng nhuận

    // ── Chuỗi hiển thị sẵn cho UI ──
    private String displayText;       // VD: "15/4 Âm lịch" hoặc "15/4 (nhuận) Âm lịch"
    private String canChi;            // Can Chi năm âm, VD: "Bính Ngọ"
}
