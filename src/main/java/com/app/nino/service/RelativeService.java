package com.app.nino.service;

import com.app.nino.exception.ResourceNotFoundException;
import com.app.nino.model.dto.request.CreateRelativeRequest;
import com.app.nino.model.dto.response.GroupSummaryResponse;
import com.app.nino.model.dto.response.RelativeDetailResponse;
import com.app.nino.model.dto.response.RelativeResponse;
import com.app.nino.model.entity.Relative;
import com.app.nino.repository.EventRepository;
import com.app.nino.repository.RelativeRepository;
import com.app.nino.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelativeService {

    private final RelativeRepository relativeRepo;
    private final UserRepository userRepo;
    private final EventRepository eventRepo;
    private final ObjectMapper       objectMapper;

    // ── GET LIST — cache 10 phút ──────────────────────────────────────────────
    @Cacheable(
        value = "relatives",
        key   = "#userId + '::' + (#groupTypeStr ?: 'ALL') + '::' + (#search ?: '')"
    )
    public List<RelativeResponse> getRelatives(Long userId, String groupTypeStr, String search) {
        Relative.GroupType groupType = groupTypeStr != null
            ? Relative.GroupType.valueOf(groupTypeStr) : null;
        return relativeRepo.findByFilters(userId, groupType, search)
            .stream().map(RelativeResponse::from).toList();
    }

    // ── GET GROUP SUMMARY ─────────────────────────────────────────────────────
    public List<GroupSummaryResponse> getGroupSummary(Long userId) {
        return relativeRepo.countByGroupType(userId)
            .stream().map(row -> GroupSummaryResponse.builder()
                .groupType(row[0].toString())
                .displayName(toDisplayName(row[0].toString()))
                .count(((Number) row[1]).longValue())
                .build())
            .toList();
    }

    // ── GET DETAIL — cache 10 phút ────────────────────────────────────────────
    @Cacheable(value = "relativeDetail", key = "#id")
    public RelativeDetailResponse getDetail(Long id, Long userId) {
        Relative relative = relativeRepo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Relative", id));

        int age = relative.getDateOfBirth() != null
            ? Period.between(relative.getDateOfBirth(), LocalDate.now()).getYears() : -1;
        long daysToBirthday = RelativeResponse.calcDaysToBirthday(relative.getDateOfBirth());

        return RelativeDetailResponse.from(relative, age, daysToBirthday,
            eventRepo.findByRelativeIdAndIsActiveTrueOrderByEventDateAsc(id));
    }

    // ── CREATE — evict list + home ────────────────────────────────────────────
    @Caching(evict = {
        @CacheEvict(value = "relatives", allEntries = true),
        @CacheEvict(value = "home",      key = "#userId"),
        @CacheEvict(value = "upcoming",  key = "#userId + '::myEvents'")
    })
    @Transactional
    public RelativeResponse create(Long userId, CreateRelativeRequest req) {
        Relative relative = Relative.builder()
            .name(req.getName())
            .nickname(req.getNickname())
            .groupType(Relative.GroupType.valueOf(req.getGroupType()))
            .gender(req.getGender() != null ? Relative.Gender.valueOf(req.getGender()) : null)
            .dateOfBirth(req.getDateOfBirth())
            .location(req.getLocation())
            .heightCm(req.getHeightCm())
            .weightKg(req.getWeightKg())
            .hobbies(toHobbiesJson(req.getHobbies()))
            .notes(req.getNotes())
            .avatarUrl(req.getAvatarUrl())
            .build();

        relative.setUser(userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId)));

        relativeRepo.save(relative);
        userRepo.incrementRelativeCount(userId);
        log.info("[Relative] Tao thanh cong: relativeId={} userId={} name={}",
            relative.getId(), userId, relative.getName());
        return RelativeResponse.from(relative);
    }

    // ── UPDATE — evict list + detail + home ───────────────────────────────────
    @Caching(evict = {
        @CacheEvict(value = "relatives",     allEntries = true),
        @CacheEvict(value = "relativeDetail", key = "#id"),
        @CacheEvict(value = "home",           key = "#userId")
    })
    @Transactional
    public RelativeResponse update(Long id, Long userId, CreateRelativeRequest req) {
        Relative relative = relativeRepo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Relative", id));

        relative.setName(req.getName());
        relative.setNickname(req.getNickname());
        relative.setGroupType(Relative.GroupType.valueOf(req.getGroupType()));
        relative.setGender(req.getGender() != null ? Relative.Gender.valueOf(req.getGender()) : null);
        relative.setDateOfBirth(req.getDateOfBirth());
        relative.setLocation(req.getLocation());
        relative.setHeightCm(req.getHeightCm());
        relative.setWeightKg(req.getWeightKg());
        relative.setHobbies(toHobbiesJson(req.getHobbies()));
        relative.setNotes(req.getNotes());
        relative.setAvatarUrl(req.getAvatarUrl());

        RelativeResponse response = RelativeResponse.from(relativeRepo.save(relative));
        log.info("[Relative] Cap nhat thanh cong: relativeId={} userId={}", id, userId);
        return response;
    }

    // ── DELETE — evict tất cả liên quan ──────────────────────────────────────
    @Caching(evict = {
        @CacheEvict(value = "relatives",     allEntries = true),
        @CacheEvict(value = "relativeDetail", key = "#id"),
        @CacheEvict(value = "home",           key = "#userId"),
        @CacheEvict(value = "events",         allEntries = true)
    })
    @Transactional
    public void delete(Long id, Long userId) {
        Relative relative = relativeRepo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Relative", id));
        relativeRepo.delete(relative);
        userRepo.decrementRelativeCount(userId);
        log.info("[Relative] Xoa thanh cong: relativeId={} userId={}", id, userId);
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private String toHobbiesJson(List<String> hobbies) {
        if (hobbies == null || hobbies.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(hobbies);
        } catch (Exception e) {
            log.warn("[Relative] Khong the serialize hobbies thanh JSON: hobbies={} error={}",
                hobbies, e.getMessage());
            return null;
        }
    }

    private String toDisplayName(String groupType) {
        return switch (groupType) {
            // Nhóm cũ — chỉ còn hiển thị cho dữ liệu có sẵn, không còn trong picker mới.
            case "GIA_DINH" -> "Gia đình";
            case "VO_CHONG" -> "Vợ/Chồng";
            case "CON_CAI"  -> "Con cái";
            case "BAN_BE"   -> "Bạn bè";
            // Danh sách quan hệ mới (theo ảnh mẫu "Quan hệ với bạn").
            case "ANH_CHI_EM"  -> "Anh/Chị/Em";
            case "BAN_THAN"    -> "Bản thân";
            case "ONG"         -> "Ông";
            case "BA"          -> "Bà";
            case "BO"          -> "Bố";
            case "ME"          -> "Mẹ";
            case "CON"         -> "Con Trai/Con Gái";
            case "NGUOI_YEU"   -> "Người yêu";
            case "NGUOI_THAN"  -> "Người Thân";
            default            -> groupType;
        };
    }
}
