package com.app.nino.service;

import com.app.nino.exception.ResourceNotFoundException;
import com.app.nino.model.dto.request.CreateRelativeRequest;
import com.app.nino.model.dto.response.GroupSummaryResponse;
import com.app.nino.model.dto.response.RelativeDetailResponse;
import com.app.nino.model.dto.response.RelativeResponse;
import com.app.nino.model.entity.Event;
import com.app.nino.model.entity.EventCategory;
import com.app.nino.model.entity.Relative;
import com.app.nino.repository.EventCategoryRepository;
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
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelativeService {

    /** Code danh mục "Sinh nhật" trong bảng event_categories — xem V13 migration. */
    private static final String BIRTHDAY_CATEGORY_CODE = "SINH_NHAT";

    private final RelativeRepository relativeRepo;
    private final UserRepository userRepo;
    private final EventRepository eventRepo;
    private final EventCategoryRepository categoryRepo;
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
    // Key PHAI gom ca userId — key theo #id thoi se lam cache-hit bo qua
    // findByIdAndUserId (check quyen so huu), lo du lieu Relative giua cac user (IDOR).
    @Cacheable(value = "relativeDetail", key = "#id + '::' + #userId")
    public RelativeDetailResponse getDetail(Long id, Long userId) {
        Relative relative = relativeRepo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Relative", id));

        int age = (relative.getDateOfBirth() != null && Boolean.TRUE.equals(relative.getDateOfBirthYearKnown()))
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
            // null (client cũ chưa gửi field này) -> coi như đã biết năm.
            .dateOfBirthYearKnown(req.getDateOfBirthYearKnown() == null || req.getDateOfBirthYearKnown())
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
        syncBirthdayEvent(relative);
        log.info("[Relative] Tao thanh cong: relativeId={} userId={} name={}",
            relative.getId(), userId, relative.getName());
        return RelativeResponse.from(relative);
    }

    // ── UPDATE — evict list + detail + home ───────────────────────────────────
    @Caching(evict = {
        @CacheEvict(value = "relatives",     allEntries = true),
        @CacheEvict(value = "relativeDetail", key = "#id + '::' + #userId"),
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
        relative.setDateOfBirthYearKnown(req.getDateOfBirthYearKnown() == null || req.getDateOfBirthYearKnown());
        relative.setLocation(req.getLocation());
        relative.setHeightCm(req.getHeightCm());
        relative.setWeightKg(req.getWeightKg());
        relative.setHobbies(toHobbiesJson(req.getHobbies()));
        relative.setNotes(req.getNotes());
        relative.setAvatarUrl(req.getAvatarUrl());

        relativeRepo.save(relative);
        syncBirthdayEvent(relative);
        RelativeResponse response = RelativeResponse.from(relative);
        log.info("[Relative] Cap nhat thanh cong: relativeId={} userId={}", id, userId);
        return response;
    }

    // ── SYNC NGƯỢC TỪ EVENT — gọi bởi EventService khi user sửa trực tiếp
    // Event "Sinh nhật" (đổi eventDate) — ghi ngày mới xuống dateOfBirth để
    // 2 màn Người thân / Sự kiện luôn khớp nhau. Evict cache thủ công vì
    // đang sửa Relative từ ngoài các method có @Cacheable/@CacheEvict ở trên.
    @Caching(evict = {
        @CacheEvict(value = "relatives",      allEntries = true),
        @CacheEvict(value = "relativeDetail", key = "#relativeId + '::' + #userId"),
        @CacheEvict(value = "home",           key = "#userId")
    })
    @Transactional
    public void syncDateOfBirthFromEvent(Long relativeId, Long userId, LocalDate eventDate) {
        relativeRepo.findByIdAndUserId(relativeId, userId).ifPresent(relative -> {
            // eventDate mang nam "lan toi" (xem nextBirthdayOccurrence), KHONG
            // phai nam sinh that -> chi lay thang/ngay, giu nguyen nam sinh
            // dang co (dung de tinh tuoi o RelativeDetailResponse.age). Neu
            // relative chua tung co dateOfBirth (khong nen xay ra, phong hoa)
            // thi lay nguyen eventDate.
            LocalDate currentDob = relative.getDateOfBirth();
            LocalDate newDob = currentDob == null
                ? eventDate
                : safeDate(currentDob.getYear(), eventDate.getMonthValue(), eventDate.getDayOfMonth());
            if (!newDob.equals(currentDob)) {
                relative.setDateOfBirth(newDob);
                relativeRepo.save(relative);
                log.info("[Relative] Dong bo ngay sinh tu Event Sinh nhat: relativeId={} newDateOfBirth={}",
                    relativeId, newDob);
            }
        });
    }

    // ── DELETE — evict tất cả liên quan ──────────────────────────────────────
    @Caching(evict = {
        @CacheEvict(value = "relatives",     allEntries = true),
        @CacheEvict(value = "relativeDetail", key = "#id + '::' + #userId"),
        @CacheEvict(value = "home",           key = "#userId"),
        @CacheEvict(value = "events",         allEntries = true)
    })
    @Transactional
    public void delete(Long id, Long userId) {
        Relative relative = relativeRepo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Relative", id));

        // FK relative_id ON DELETE SET NULL — nếu không vô hiệu hoá trước,
        // Event Sinh nhật liên kết sẽ "mồ côi" (relativeId=null) và tồn tại
        // mãi trong tab Sự kiện dù người thân đã bị xoá.
        eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(id, BIRTHDAY_CATEGORY_CODE)
            .ifPresent(event -> {
                event.setIsActive(false);
                eventRepo.save(event);
            });

        relativeRepo.delete(relative);
        userRepo.decrementRelativeCount(userId);
        log.info("[Relative] Xoa thanh cong: relativeId={} userId={}", id, userId);
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    /**
     * Đồng bộ Event "Sinh nhật" liên kết với [relative] theo đúng
     * dateOfBirth hiện tại — gọi sau khi create/update Relative. Mỗi
     * người thân có tối đa 1 Event loại này:
     * - Chưa có dateOfBirth: không làm gì (chưa từng có/không cần Event).
     * - Có dateOfBirth, chưa có Event: tạo mới (lặp hàng năm YEARLY).
     * - Có dateOfBirth, đã có Event: chỉ cập nhật eventDate, giữ nguyên
     *   title/giờ/nhắc nhở người dùng có thể đã tự sửa.
     * - dateOfBirth bị xoá (null), đã có Event: vô hiệu hoá (soft-delete)
     *   Event đó, giống hệt EventService.delete().
     * Ghi thẳng qua eventRepo (không gọi EventService.create/update) để
     * tránh gọi vòng lại sang RelativeService.syncDateOfBirthFromEvent.
     */
    private void syncBirthdayEvent(Relative relative) {
        LocalDate dob = relative.getDateOfBirth();
        Optional<Event> existing = eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(
            relative.getId(), BIRTHDAY_CATEGORY_CODE);

        if (dob == null) {
            existing.ifPresent(event -> {
                event.setIsActive(false);
                eventRepo.save(event);
                userRepo.decrementEventCount(relative.getUser().getId());
                relativeRepo.decrementRelativeEventCount(relative.getId());
                log.info("[Relative] Da xoa ngay sinh -> vo hieu hoa Event Sinh nhat: eventId={} relativeId={}",
                    event.getId(), relative.getId());
            });
            return;
        }

        // event_date cua Event Sinh nhat (recurrence YEARLY) la NGAY LAN TOI
        // sap dien ra (nam nay/nam sau) — KHONG gan thang dateOfBirth (nam
        // sinh that). Backend khong co scheduler roll-forward nam cho
        // YEARLY thuong (khac LunarRecurrenceScheduler danh cho am lich),
        // va EventService.toResponse tinh daysUntil = hieu so ngay tho ->
        // gan nam sinh that se khien Event moi tao luon "DA QUA" ngay lap
        // tuc. Quy uoc nay khop du lieu seed co san (V10_...sql).
        LocalDate nextOccurrence = nextBirthdayOccurrence(dob, LocalDate.now());

        if (existing.isPresent()) {
            Event event = existing.get();
            if (!nextOccurrence.equals(event.getEventDate())) {
                event.setEventDate(nextOccurrence);
                eventRepo.save(event);
            }
            return;
        }

        EventCategory category = categoryRepo.findByCode(BIRTHDAY_CATEGORY_CODE)
            .orElseThrow(() -> new IllegalStateException(
                "Thieu danh muc he thong '" + BIRTHDAY_CATEGORY_CODE + "' trong bang event_categories"));
        String displayName = relative.getNickname() != null ? relative.getNickname() : relative.getName();
        Event event = Event.builder()
            .user(relative.getUser())
            .relative(relative)
            .title("Sinh nhật " + displayName)
            .category(category)
            .eventDate(nextOccurrence)
            .isRecurring(true)
            .recurrenceType(Event.RecurrenceType.YEARLY)
            .isActive(true)
            .build();
        eventRepo.save(event);
        userRepo.incrementEventCount(relative.getUser().getId());
        relativeRepo.incrementEventCount(relative.getId());
        log.info("[Relative] Tu tao Event Sinh nhat: relativeId={} eventDate={}", relative.getId(), nextOccurrence);
    }

    /**
     * Ngày sinh nhật lần tới tính từ hôm nay — cùng tháng/ngày với [dob],
     * năm là năm nay nếu chưa qua, năm sau nếu đã qua. Dùng cho eventDate
     * của Event Sinh nhật (xem giải thích ở syncBirthdayEvent). Ngày
     * 29/2 ở năm không nhuận được kẹp về 28/2 để tránh crash
     * (LocalDate.withYear ném DateTimeException với năm không nhuận).
     */
    static LocalDate nextBirthdayOccurrence(LocalDate dob, LocalDate today) {
        LocalDate next = safeDate(today.getYear(), dob.getMonthValue(), dob.getDayOfMonth());
        // Hôm nay đúng là sinh nhật -> vẫn tính là "lần tới" (0 ngày), KHÔNG
        // nhảy sang năm sau — khớp EventListSort._isPast (daysUntil == 0
        // không tính là đã qua) và chuỗi "Sinh nhật hôm nay! 🎂" ở mobile.
        if (next.isBefore(today)) {
            next = safeDate(today.getYear() + 1, dob.getMonthValue(), dob.getDayOfMonth());
        }
        return next;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        int maxDay = java.time.YearMonth.of(year, month).lengthOfMonth();
        return LocalDate.of(year, month, Math.min(day, maxDay));
    }

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
