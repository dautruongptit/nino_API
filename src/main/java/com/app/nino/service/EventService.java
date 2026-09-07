package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.exception.ForbiddenException;
import com.app.nino.exception.ResourceNotFoundException;
import com.app.nino.model.dto.request.CreateEventRequest;
import com.app.nino.model.dto.request.ReminderRequest;
import com.app.nino.model.dto.response.EventCategoryResponse;
import com.app.nino.model.dto.response.EventResponse;
import com.app.nino.model.dto.response.ReminderResponse;
import com.app.nino.model.entity.*;
import com.app.nino.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {

    /** Code danh mục "Sinh nhật" trong bảng event_categories — xem V13 migration. */
    private static final String BIRTHDAY_CATEGORY_CODE = "SINH_NHAT";

    private final EventRepository eventRepo;
    private final RelativeRepository relativeRepo;
    private final UserRepository userRepo;
    private final EventParticipantRepository participantRepo;
    private final EventCategoryRepository categoryRepo;
    private final NotificationRepository notificationRepo;
    private final RelativeService relativeService;

    // ── GET CATEGORIES (picker "Danh mục" khi Thêm/Sửa sự kiện) ─────────
    // Chỉ trả danh mục hệ thống (isSystem=true) — danh mục user tự tạo
    // (is_system=0) dành cho tính năng tương lai, chưa có UI tạo/quản lý.
    public List<EventCategoryResponse> getCategories() {
        return categoryRepo.findByIsSystemTrueOrderBySortOrderAsc().stream()
            .map(EventCategoryResponse::from)
            .collect(Collectors.toList());
    }

    // ── GET UPCOMING (màn hình Home – tối đa limit sự kiện) ─────────────
    public List<EventResponse> getUpcoming(Long userId, int limit) {
        LocalDate today  = LocalDate.now();
        LocalDate future = today.plusDays(90);
        return eventRepo
                .findUpcoming(userId, today, future, PageRequest.of(0, limit))
                .stream()
                .map(e -> toResponse(e, today))
                .collect(Collectors.toList());
    }

    // ── GET LIST (filter đa điều kiện) ──────────────────────────────────
    public List<EventResponse> getEvents(Long userId, Long categoryId,
                                         Long relativeId, Integer month, Integer year) {
        LocalDate today = LocalDate.now();
        return eventRepo
                .findFiltered(userId, categoryId, relativeId, month, year)
                .stream()
                .map(e -> toResponse(e, today))
                .collect(Collectors.toList());
    }

    // ── GET DETAIL ───────────────────────────────────────────────────────
    public EventResponse getById(Long id, Long userId) {
        Event e = findByIdAndOwner(id, userId);
        return toResponse(e, LocalDate.now());
    }

    // ── CREATE ───────────────────────────────────────────────────────────
    @Transactional
    public EventResponse create(Long userId, CreateEventRequest req) {
        log.debug("[Event] Create request: userId={} title={} categoryId={}",
            userId, req.getTitle(), req.getCategoryId());

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Nguoi dung khong ton tai"));

        EventCategory category = categoryRepo.findById(req.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Danh muc su kien khong ton tai"));

        // Resolve người thân (nullable – null = sự kiện bản thân)
        Relative relative = null;
        if (req.getRelativeId() != null) {
            relative = relativeRepo
                    .findByIdAndUserId(req.getRelativeId(), userId)
                    .orElseThrow(() ->
                            new ResourceNotFoundException("Nguoi than khong ton tai"));
        }

        // Chống trùng Event "Sinh nhật": RelativeService.syncBirthdayEvent()
        // đã tự sinh 1 Event loại này khi thêm/sửa ngày sinh cho người thân.
        // Nếu người dùng lại tự tay tạo thêm 1 Event danh mục Sinh nhật cho
        // ĐÚNG người thân đã có sẵn -> cập nhật event đã có thay vì insert
        // thêm bản ghi mới (tránh 2 Event + 2 bộ nhắc nhở + 2 thông báo
        // trùng lặp cho cùng 1 sinh nhật).
        if (relative != null && BIRTHDAY_CATEGORY_CODE.equals(category.getCode())) {
            var existingBirthdayEvent = eventRepo
                    .findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(relative.getId(), BIRTHDAY_CATEGORY_CODE);
            if (existingBirthdayEvent.isPresent()) {
                log.info("[Event] Da co san Event Sinh nhat (id={}) cho relativeId={} -> cap nhat thay vi tao trung",
                    existingBirthdayEvent.get().getId(), relative.getId());
                return update(existingBirthdayEvent.get().getId(), userId, req);
            }
        }

        Event.RecurrenceType recurrenceType = resolveRecurrenceType(req.getRecurrenceType());
        validateRecurrenceFields(recurrenceType, req);

        Event event = Event.builder()
                .user(user)
                .relative(relative)
                .title(req.getTitle())
                .category(category)
                .eventDate(req.getEventDate())
                .eventTime(req.getEventTime())
                .isRecurring(Boolean.TRUE.equals(req.getIsRecurring()))
                .recurrenceType(recurrenceType)
                .lunarDay(recurrenceType == Event.RecurrenceType.LUNAR_YEARLY ? req.getLunarDay() : null)
                .lunarMonth(recurrenceType == Event.RecurrenceType.LUNAR_YEARLY ? req.getLunarMonth() : null)
                .customIntervalValue(recurrenceType == Event.RecurrenceType.CUSTOM ? req.getCustomIntervalValue() : null)
                .customIntervalUnit(recurrenceType == Event.RecurrenceType.CUSTOM
                        ? Event.CustomIntervalUnit.valueOf(req.getCustomIntervalUnit()) : null)
                .notes(req.getNotes())
                .isActive(true)
                .build();
        saveParticipants(event, userId, req.getParticipantIds());
        // Map reminders từ request
        if (req.getReminders() != null && !req.getReminders().isEmpty()) {
            List<EventReminder> reminders = buildReminders(req.getReminders(), event);
            event.setReminders(reminders);
        }
        ensureDefaultReminderWhenNone(event);

        Event saved = eventRepo.save(event);

        // Cập nhật cache counter
        userRepo.incrementEventCount(userId);
        if (relative != null)
            relativeRepo.incrementEventCount(relative.getId());

        syncRelativeBirthdayIfNeeded(category, relative, userId, saved.getEventDate());

        log.info("[Event] Tao thanh cong: eventId={} userId={} title={}",
            saved.getId(), userId, saved.getTitle());
        return toResponse(saved, LocalDate.now());
    }

    // ── UPDATE ───────────────────────────────────────────────────────────
    @Transactional
    public EventResponse update(Long id, Long userId, CreateEventRequest req) {
        log.debug("[Event] Update request: eventId={} userId={}", id, userId);
        Event event = findByIdAndOwner(id, userId);

        EventCategory category = categoryRepo.findById(req.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Danh muc su kien khong ton tai"));

        // Resolve người thân mới (có thể thay đổi)
        Relative newRelative = null;
        if (req.getRelativeId() != null) {
            newRelative = relativeRepo
                    .findByIdAndUserId(req.getRelativeId(), userId)
                    .orElseThrow(() ->
                            new ResourceNotFoundException("Nguoi than khong ton tai"));
        }

        Event.RecurrenceType recurrenceType = resolveRecurrenceType(req.getRecurrenceType());
        validateRecurrenceFields(recurrenceType, req);

        event.setRelative(newRelative);
        event.setTitle(req.getTitle());
        event.setCategory(category);
        event.setEventDate(req.getEventDate());
        event.setEventTime(req.getEventTime());
        event.setIsRecurring(Boolean.TRUE.equals(req.getIsRecurring()));
        event.setRecurrenceType(recurrenceType);
        event.setLunarDay(recurrenceType == Event.RecurrenceType.LUNAR_YEARLY ? req.getLunarDay() : null);
        event.setLunarMonth(recurrenceType == Event.RecurrenceType.LUNAR_YEARLY ? req.getLunarMonth() : null);
        event.setCustomIntervalValue(recurrenceType == Event.RecurrenceType.CUSTOM ? req.getCustomIntervalValue() : null);
        event.setCustomIntervalUnit(recurrenceType == Event.RecurrenceType.CUSTOM
                ? Event.CustomIntervalUnit.valueOf(req.getCustomIntervalUnit()) : null);
        event.setNotes(req.getNotes());

        // Xoá reminders cũ, tạo lại từ request. Nếu một reminder cũ đã có
        // thông báo bắn ra (notifications.reminder_id trỏ vào nó), gỡ liên
        // kết đó trước — orphanRemoval sẽ DELETE reminder cũ khi flush, và
        // MySQL sẽ chặn DELETE đó vì khoá ngoại nếu còn notification tham
        // chiếu (SQL error 1451).
        //
        // Chỉ thay thế khi list KHÔNG rỗng (giống create()) — client gửi
        // "reminders": [] (thường do màn sửa không load lại reminder cũ
        // trước khi submit) từng bị hiểu là "xoá hết", âm thầm mất reminder
        // mà không có gì thay thế. Muốn xoá hết reminder của 1 event, dùng
        // API xoá riêng thay vì gửi mảng rỗng qua update.
        if (req.getReminders() != null && !req.getReminders().isEmpty()) {
            List<Long> oldReminderIds = event.getReminders().stream()
                    .map(EventReminder::getId)
                    .filter(reminderId -> reminderId != null)
                    .collect(Collectors.toList());
            if (!oldReminderIds.isEmpty()) {
                notificationRepo.detachReminders(oldReminderIds);
            }
            event.getReminders().clear();
            event.getReminders().addAll(buildReminders(req.getReminders(), event));
        }
        ensureDefaultReminderWhenNone(event);

        Event saved = eventRepo.save(event);
        syncRelativeBirthdayIfNeeded(category, newRelative, userId, saved.getEventDate());

        EventResponse response = toResponse(saved, LocalDate.now());
        log.info("[Event] Cap nhat thanh cong: eventId={} userId={}", id, userId);
        return response;
    }

    // ── DELETE (soft delete: isActive = false) ───────────────────────────
    @Transactional
    public void delete(Long id, Long userId) {
        Event event = findByIdAndOwner(id, userId);
        event.setIsActive(false);
        eventRepo.save(event);
        userRepo.decrementEventCount(userId);
        if (event.getRelative() != null)
            relativeRepo.decrementRelativeEventCount(event.getRelative().getId());
        log.info("[Event] Xoa (soft delete) thanh cong: eventId={} userId={}", id, userId);
    }

    // ── PRIVATE HELPERS ─────────────────────────────────────────────────

    /**
     * Event "Sinh nhật" và Relative.dateOfBirth là 1 — nếu Event vừa lưu
     * thuộc danh mục Sinh nhật và có gắn người thân, đẩy eventDate mới
     * ngược lại thành dateOfBirth của người đó (chiều Relative -> Event
     * nằm ở RelativeService.syncBirthdayEvent).
     */
    private void syncRelativeBirthdayIfNeeded(EventCategory category, Relative relative, Long userId, LocalDate eventDate) {
        if (relative == null) return;
        if (!BIRTHDAY_CATEGORY_CODE.equals(category.getCode())) return;
        relativeService.syncDateOfBirthFromEvent(relative.getId(), userId, eventDate);
    }

    /** Parse recurrenceType string -> enum, báo lỗi rõ ràng cho client thay vì 500. */
    private Event.RecurrenceType resolveRecurrenceType(String raw) {
        if (raw == null) return null;
        try {
            return Event.RecurrenceType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Kieu lap lai khong hop le: " + raw);
        }
    }

    /** Kiểm tra các field bắt buộc đi kèm theo từng loại recurrenceType. */
    private void validateRecurrenceFields(Event.RecurrenceType recurrenceType, CreateEventRequest req) {
        if (recurrenceType != Event.RecurrenceType.CUSTOM) return;

        if (req.getCustomIntervalValue() == null || req.getCustomIntervalValue() < 1) {
            throw new BadRequestException(
                "Vui long chon so lan lap lai (customIntervalValue >= 1) cho kieu lap tuy chinh");
        }
        if (req.getCustomIntervalUnit() == null) {
            throw new BadRequestException(
                "Vui long chon don vi lap lai (customIntervalUnit) cho kieu lap tuy chinh");
        }
        try {
            Event.CustomIntervalUnit.valueOf(req.getCustomIntervalUnit());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Don vi lap lai tuy chinh khong hop le: " + req.getCustomIntervalUnit());
        }
    }

    private Event findByIdAndOwner(Long id, Long userId) {
        Event e = eventRepo.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Su kien khong ton tai: " + id));
        if (!e.getUser().getId().equals(userId)) {
            log.warn("[Event] Truy cap bi tu choi: eventId={} ownerUserId={} requestUserId={}",
                id, e.getUser().getId(), userId);
            throw new ForbiddenException("Ban khong co quyen truy cap su kien nay");
        }
        return e;
    }

    /**
     * Không chọn nhắc nhở nào -> mặc định nhắc đúng vào giờ đã chọn cho sự
     * kiện (remind*Before đều null -> computeTriggerTime trả về đúng
     * eventDate/eventTime), thay vì im lặng không nhắc gì.
     */
    private void ensureDefaultReminderWhenNone(Event event) {
        if (!event.getReminders().isEmpty()) return;
        event.getReminders().add(EventReminder.builder()
                .event(event)
                .isEnabled(true)
                .build());
    }

    /** Build danh sách EventReminder từ request list. */
    private List<EventReminder> buildReminders(
            List<ReminderRequest> requests, Event event) {
        List<EventReminder> result = new ArrayList<>();
        for (ReminderRequest r : requests) {
            result.add(EventReminder.builder()
                    .event(event)
                    .remindDaysBefore(r.getRemindDaysBefore())
                    .remindHoursBefore(r.getRemindHoursBefore())
                    .remindMinutesBefore(r.getRemindMinutesBefore())
                    .repeatIntervalMinutes(r.getRepeatIntervalMinutes())
                    .isEnabled(Boolean.TRUE.equals(r.getIsEnabled()))
                    .build());
        }
        return result;
    }

    /** Map Event entity -> EventResponse DTO. */
    public EventResponse toResponse(Event e, LocalDate today) {
        long daysUntil = ChronoUnit.DAYS.between(today, e.getEventDate());

        List<ReminderResponse> reminders = (e.getReminders() == null)
                ? List.of()
                : e.getReminders().stream()
                .map(r -> ReminderResponse.builder()
                        .id(r.getId())
                        .remindDaysBefore(r.getRemindDaysBefore())
                        .remindHoursBefore(r.getRemindHoursBefore())
                        .remindMinutesBefore(r.getRemindMinutesBefore())
                        .repeatIntervalMinutes(r.getRepeatIntervalMinutes())
                        .isEnabled(r.getIsEnabled())
                        .build())
                .collect(Collectors.toList());

        EventResponse response = EventResponse.builder()
                .id(e.getId())
                .title(e.getTitle())
                .categoryId(e.getCategory().getId())
                .categoryCode(e.getCategory().getCode())
                .categoryName(e.getCategory().getDisplayName())
                .categoryIcon(e.getCategory().getIcon())
                .categoryColor(e.getCategory().getColorHex())
                .eventDate(e.getEventDate())
                .eventTime(e.getEventTime())
                .isRecurring(e.getIsRecurring())
                .recurrenceType(e.getRecurrenceType() != null
                        ? e.getRecurrenceType().name() : null)
                .lunarDay(e.getLunarDay())
                .lunarMonth(e.getLunarMonth())
                .customIntervalValue(e.getCustomIntervalValue())
                .customIntervalUnit(e.getCustomIntervalUnit() != null
                        ? e.getCustomIntervalUnit().name() : null)
                .notes(e.getNotes())
                .relativeId(e.getRelative() != null ? e.getRelative().getId() : null)
                .relativeName(e.getRelative() != null ? e.getRelative().getName() : null)
                .relativeGroupType(e.getRelative() != null
                        ? e.getRelative().getGroupType().name() : null)
                .daysUntil(daysUntil)
                .reminders(reminders)
                .build();

        List<EventParticipant> participants = participantRepo.findByEventId(e.getId());
        if (!participants.isEmpty()) {
            response.setParticipants(participants.stream()
                    .map(ep -> EventResponse.ParticipantSummary.builder()
                            .id(ep.getRelative().getId())
                            .name(ep.getRelative().getName())
                            .avatarUrl(ep.getRelative().getAvatarUrl())
                            .build())
                    .toList());
        }
        return response;
    }


    private void saveParticipants(Event event, Long userId, List<Long> relativeIds) {
       if (relativeIds == null || relativeIds.isEmpty()) return;
       List<Relative> relatives = relativeRepo.findAllById(relativeIds).stream()
           .filter(r -> r.getUser().getId().equals(userId))  // chi cho phep relative cua chinh user
           .toList();

       List<EventParticipant> participants = relatives.stream().map(r -> EventParticipant.builder()
                .event(event)
                .relative(r)
               .build())
           .toList();

        participantRepo.saveAll(participants);
    }


}

