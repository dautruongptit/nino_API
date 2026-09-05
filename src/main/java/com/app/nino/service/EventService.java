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

    private final EventRepository eventRepo;
    private final RelativeRepository relativeRepo;
    private final UserRepository userRepo;
    private final EventParticipantRepository participantRepo;
    private final EventCategoryRepository categoryRepo;
    private final NotificationRepository notificationRepo;

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

        Event saved = eventRepo.save(event);

        // Cập nhật cache counter
        userRepo.incrementEventCount(userId);
        if (relative != null)
            relativeRepo.incrementEventCount(relative.getId());

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
        if (req.getReminders() != null) {
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

        EventResponse response = toResponse(eventRepo.save(event), LocalDate.now());
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

    /** Build danh sách EventReminder từ request list. */
    private List<EventReminder> buildReminders(
            List<ReminderRequest> requests, Event event) {
        List<EventReminder> result = new ArrayList<>();
        for (ReminderRequest r : requests) {
            result.add(EventReminder.builder()
                    .event(event)
                    .remindDaysBefore(r.getRemindDaysBefore())
                    .remindHoursBefore(r.getRemindHoursBefore())
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
                        .repeatIntervalMinutes(r.getRepeatIntervalMinutes())
                        .isEnabled(r.getIsEnabled())
                        .build())
                .collect(Collectors.toList());

        EventResponse response = EventResponse.builder()
                .id(e.getId())
                .title(e.getTitle())
                .categoryId(e.getCategory().getId())
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

