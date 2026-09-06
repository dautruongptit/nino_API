package com.app.nino.model.dto.response;

import com.app.nino.model.entity.Event;
import com.app.nino.model.entity.Relative;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class RelativeDetailResponse {

    private Long id;
    private String name;
    private String nickname;
    private String groupType;
    private String gender;
    // Ngày sinh tách 3 phần — xem Relative.birthMonth/birthDay/birthYear.
    private Integer birthMonth;
    private Integer birthDay;
    private Integer birthYear;
    private Integer age;
    private Long daysToBirthday;
    private String location;
    private BigDecimal heightCm;
    private BigDecimal weightKg;
    private List<String> hobbies;
    private String notes;
    private String avatarUrl;
    private Integer totalEvents;
    private List<RelatedEventSummary> events;

    public static RelativeDetailResponse from(Relative r, int age, long daysToBirthday,
                                               List<Event> relatedEvents) {
        return RelativeDetailResponse.builder()
            .id(r.getId())
            .name(r.getName())
            .nickname(r.getNickname())
            .groupType(r.getGroupType() != null ? r.getGroupType().name() : null)
            .gender(r.getGender() != null ? r.getGender().name() : null)
            .birthMonth(r.getBirthMonth())
            .birthDay(r.getBirthDay())
            .birthYear(r.getBirthYear())
            .age(age >= 0 ? age : null)
            .daysToBirthday(daysToBirthday)
            .location(r.getLocation())
            .heightCm(r.getHeightCm())
            .weightKg(r.getWeightKg())
            .hobbies(RelativeResponse.parseHobbies(r.getHobbies()))
            .notes(r.getNotes())
            .avatarUrl(r.getAvatarUrl())
            .totalEvents(r.getTotalEvents())
            .events(relatedEvents.stream().map(RelatedEventSummary::from).toList())
            .build();
    }

    @Data
    @Builder
    public static class RelatedEventSummary {
        private Long id;
        private String title;
        private String categoryCode;
        // Thiếu 2 field này khiến EventModel.fromJson ở mobile không có
        // categoryColor -> colorFromHex('') fallback về xám cho MỌI sự kiện
        // ở mục "Sự kiện của..." màn Chi tiết người thân, bất kể danh mục
        // thật là gì (VD "Kỷ niệm" đáng lẽ tím vẫn hiện xám) — trong khi
        // màn Sự kiện chính (GET /events, dùng EventResponse đầy đủ) vẫn
        // lên màu đúng.
        private String categoryIcon;
        private String categoryColor;
        private LocalDate eventDate;
        private Boolean isActive;

        public static RelatedEventSummary from(Event e) {
            return RelatedEventSummary.builder()
                .id(e.getId())
                .title(e.getTitle())
                .categoryCode(e.getCategory() != null ? e.getCategory().getCode() : null)
                .categoryIcon(e.getCategory() != null ? e.getCategory().getIcon() : null)
                .categoryColor(e.getCategory() != null ? e.getCategory().getColorHex() : null)
                .eventDate(e.getEventDate())
                .isActive(e.getIsActive())
                .build();
        }
    }
}
