package com.app.nino.model.dto.response;

import com.app.nino.model.entity.Relative;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class RelativeResponse {

    private Long id;
    private String name;
    private String nickname;
    private String groupType;
    private String gender;
    private LocalDate dateOfBirth;
    private String location;
    private BigDecimal heightCm;
    private BigDecimal weightKg;
    private List<String> hobbies;
    private String notes;
    private String avatarUrl;
    private Integer totalEvents;
    private Long daysToBirthday;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static RelativeResponse from(Relative r) {
        return RelativeResponse.builder()
            .id(r.getId())
            .name(r.getName())
            .nickname(r.getNickname())
            .groupType(r.getGroupType() != null ? r.getGroupType().name() : null)
            .gender(r.getGender() != null ? r.getGender().name() : null)
            .dateOfBirth(r.getDateOfBirth())
            .location(r.getLocation())
            .heightCm(r.getHeightCm())
            .weightKg(r.getWeightKg())
            .hobbies(parseHobbies(r.getHobbies()))
            .notes(r.getNotes())
            .avatarUrl(r.getAvatarUrl())
            .totalEvents(r.getTotalEvents())
            .daysToBirthday(calcDaysToBirthday(r.getDateOfBirth()))
            .build();
    }

    public static List<String> parseHobbies(String hobbiesJson) {
        if (hobbiesJson == null || hobbiesJson.isBlank()) return List.of();
        try {
            return MAPPER.readValue(hobbiesJson, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static Long calcDaysToBirthday(LocalDate dob) {
        if (dob == null) return -1L;
        LocalDate today = LocalDate.now();
        LocalDate next = dob.withYear(today.getYear());
        if (!next.isAfter(today)) next = next.plusYears(1);
        return java.time.temporal.ChronoUnit.DAYS.between(today, next);
    }
}
