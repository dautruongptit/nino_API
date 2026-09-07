package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.model.dto.request.CreateEventRequest;
import com.app.nino.model.dto.response.EventResponse;
import com.app.nino.model.entity.Event;
import com.app.nino.model.entity.EventCategory;
import com.app.nino.model.entity.EventReminder;
import com.app.nino.model.entity.Relative;
import com.app.nino.model.entity.User;
import com.app.nino.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock private EventRepository eventRepo;
    @Mock private RelativeRepository relativeRepo;
    @Mock private UserRepository userRepo;
    @Mock private EventParticipantRepository participantRepo;
    @Mock private EventCategoryRepository categoryRepo;
    @Mock private NotificationRepository notificationRepo;
    @Mock private RelativeService relativeService;

    @InjectMocks
    private EventService service;

    @BeforeEach
    void setUp() {
        lenient().when(userRepo.findById(1L)).thenReturn(java.util.Optional.of(User.builder().id(1L).build()));
        lenient().when(categoryRepo.findById(2L)).thenReturn(java.util.Optional.of(
            EventCategory.builder().id(2L).displayName("Khác").icon("more").colorHex("#000000").build()));
        lenient().when(eventRepo.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(99L);
            return e;
        });
        lenient().when(participantRepo.findByEventId(any())).thenReturn(List.of());
    }

    private CreateEventRequest baseRequest(String recurrenceType) {
        CreateEventRequest req = new CreateEventRequest();
        req.setTitle("Test");
        req.setCategoryId(2L);
        req.setEventDate(LocalDate.now());
        req.setIsRecurring(true);
        req.setRecurrenceType(recurrenceType);
        return req;
    }

    @Test
    void create_withDailyRecurrence_savesRecurrenceType() {
        EventResponse res = service.create(1L, baseRequest("DAILY"));

        assertEquals("DAILY", res.getRecurrenceType());
    }

    @Test
    void create_withHourlyRecurrence_savesRecurrenceType() {
        EventResponse res = service.create(1L, baseRequest("HOURLY"));

        assertEquals("HOURLY", res.getRecurrenceType());
    }

    @Test
    void create_withCustomRecurrence_savesIntervalValueAndUnit() {
        CreateEventRequest req = baseRequest("CUSTOM");
        req.setCustomIntervalValue(3);
        req.setCustomIntervalUnit("WEEK");

        EventResponse res = service.create(1L, req);

        assertEquals("CUSTOM", res.getRecurrenceType());
        assertEquals(3, res.getCustomIntervalValue());
        assertEquals("WEEK", res.getCustomIntervalUnit());
    }

    @Test
    void create_withCustomRecurrenceMissingValue_throwsBadRequest() {
        CreateEventRequest req = baseRequest("CUSTOM");
        req.setCustomIntervalUnit("WEEK");

        assertThrows(BadRequestException.class, () -> service.create(1L, req));
    }

    @Test
    void create_withCustomRecurrenceMissingUnit_throwsBadRequest() {
        CreateEventRequest req = baseRequest("CUSTOM");
        req.setCustomIntervalValue(3);

        assertThrows(BadRequestException.class, () -> service.create(1L, req));
    }

    @Test
    void create_withInvalidRecurrenceType_throwsBadRequest() {
        CreateEventRequest req = baseRequest("NOT_A_REAL_TYPE");

        assertThrows(BadRequestException.class, () -> service.create(1L, req));
    }

    @Test
    void create_withLunarYearlyRecurrence_savesLunarDayAndMonth() {
        CreateEventRequest req = baseRequest("LUNAR_YEARLY");
        req.setLunarDay(20);
        req.setLunarMonth(7);

        EventResponse res = service.create(1L, req);

        assertEquals("LUNAR_YEARLY", res.getRecurrenceType());
        assertEquals(20, res.getLunarDay());
        assertEquals(7, res.getLunarMonth());
    }

    @Test
    void create_withoutReminders_addsDefaultReminderAtEventTime() {
        // Không chọn nhắc nhở nào -> mặc định nhắc đúng vào giờ đã chọn cho
        // sự kiện (không remind*Before nào), thay vì im lặng không nhắc gì.
        CreateEventRequest req = baseRequest(null);

        EventResponse res = service.create(1L, req);

        assertEquals(1, res.getReminders().size());
        var reminder = res.getReminders().get(0);
        assertEquals(null, reminder.getRemindDaysBefore());
        assertEquals(null, reminder.getRemindHoursBefore());
        assertEquals(null, reminder.getRemindMinutesBefore());
    }

    @Test
    void update_whenEventHasNoRemindersAndRequestOmitsThem_addsDefaultReminderAtEventTime() {
        // Sự kiện cũ không có reminder nào (VD tạo trước khi có default này)
        // -> sửa sự kiện (không đụng tới reminders) cũng tự vá thêm 1 default.
        User owner = User.builder().id(1L).build();
        Event existing = Event.builder()
                .id(13L)
                .user(owner)
                .reminders(new ArrayList<>())
                .build();
        when(eventRepo.findById(13L)).thenReturn(java.util.Optional.of(existing));

        service.update(13L, 1L, baseRequest(null));

        assertEquals(1, existing.getReminders().size());
        assertEquals(null, existing.getReminders().get(0).getRemindHoursBefore());
    }

    @Test
    void update_replacingReminders_detachesNotificationsFromOldRemindersFirst() {
        // Sự kiện đã tồn tại, có 1 reminder cũ (id=100) — mô phỏng đúng
        // trạng thái gây lỗi thật: orphanRemoval sẽ DELETE reminder này khi
        // event.getReminders().clear() chạy, và nếu một Notification còn
        // trỏ reminder_id=100 thì DB sẽ chặn (FK, SQL error 1451).
        User owner = User.builder().id(1L).build();
        EventReminder oldReminder = EventReminder.builder().id(100L).remindDaysBefore(7).build();
        Event existing = Event.builder()
                .id(10L)
                .user(owner)
                .reminders(new ArrayList<>(List.of(oldReminder)))
                .build();
        oldReminder.setEvent(existing);
        when(eventRepo.findById(10L)).thenReturn(java.util.Optional.of(existing));

        var newReminder = new com.app.nino.model.dto.request.ReminderRequest();
        newReminder.setRemindDaysBefore(3);
        newReminder.setIsEnabled(true);
        CreateEventRequest req = baseRequest(null);
        req.setReminders(List.of(newReminder));

        service.update(10L, 1L, req);

        // Đã gỡ liên kết reminder_id=100 khỏi mọi notification TRƯỚC KHI
        // reminders cũ bị xoá — thứ tự này là điều khiến DELETE không còn
        // vi phạm khoá ngoại.
        verify(notificationRepo).detachReminders(List.of(100L));
        assertEquals(1, existing.getReminders().size());
        assertEquals(3, existing.getReminders().get(0).getRemindDaysBefore());
    }

    @Test
    void update_withEmptyRemindersList_doesNotWipeExistingReminders() {
        // Bug that lost a real reminder in prod: create() only touches
        // reminders when the list is non-empty, but update() used to act on
        // any non-null list — including an empty one. Since Event.reminders
        // has orphanRemoval=true, an update request carrying "reminders: []"
        // (e.g. an edit screen that doesn't round-trip the existing reminder)
        // silently deleted the reminder with no replacement.
        User owner = User.builder().id(1L).build();
        EventReminder oldReminder = EventReminder.builder().id(100L).remindHoursBefore(1).build();
        Event existing = Event.builder()
                .id(12L)
                .user(owner)
                .reminders(new ArrayList<>(List.of(oldReminder)))
                .build();
        oldReminder.setEvent(existing);
        when(eventRepo.findById(12L)).thenReturn(java.util.Optional.of(existing));

        CreateEventRequest req = baseRequest(null);
        req.setReminders(List.of());

        service.update(12L, 1L, req);

        assertEquals(1, existing.getReminders().size());
        assertEquals(100L, existing.getReminders().get(0).getId());
        verify(notificationRepo, never()).detachReminders(any());
    }

    @Test
    void update_replacingReminders_whenOldReminderHasNoId_doesNotCallDetach() {
        // Reminder cũ chưa từng persist (id null, VD giữa lúc build entity) —
        // không có gì để gỡ liên kết, không nên gọi detachReminders với danh
        // sách rỗng (JPQL "IN ()" sẽ lỗi cú pháp).
        User owner = User.builder().id(1L).build();
        EventReminder oldReminder = EventReminder.builder().remindDaysBefore(7).build();
        Event existing = Event.builder()
                .id(11L)
                .user(owner)
                .reminders(new ArrayList<>(List.of(oldReminder)))
                .build();
        when(eventRepo.findById(11L)).thenReturn(java.util.Optional.of(existing));

        var newReminder = new com.app.nino.model.dto.request.ReminderRequest();
        newReminder.setRemindDaysBefore(1);
        CreateEventRequest req = baseRequest(null);
        req.setReminders(List.of(newReminder));

        service.update(11L, 1L, req);

        verify(notificationRepo, never()).detachReminders(any());
    }

    // ── SYNC NGƯỢC SANG RELATIVE — sửa Event "Sinh nhật" thì dateOfBirth
    // của người thân đó cũng phải đổi theo (xem RelativeServiceTest cho
    // chiều ngược lại: Relative -> Event). ────────────────────────────────

    private EventCategory birthdayCategory() {
        return EventCategory.builder().id(3L).code("SINH_NHAT").displayName("Sinh nhật")
            .icon("cake").colorHex("#FF6B6B").build();
    }

    @Test
    void create_withBirthdayCategoryAndRelative_syncsRelativeDateOfBirth() {
        Relative relative = Relative.builder().id(20L).user(User.builder().id(1L).build())
            .groupType(Relative.GroupType.ME).build();
        when(relativeRepo.findByIdAndUserId(20L, 1L)).thenReturn(java.util.Optional.of(relative));
        when(categoryRepo.findById(3L)).thenReturn(java.util.Optional.of(birthdayCategory()));

        CreateEventRequest req = baseRequest(null);
        req.setCategoryId(3L);
        req.setRelativeId(20L);
        req.setEventDate(LocalDate.of(1970, 5, 20));

        service.create(1L, req);

        verify(relativeService).syncDateOfBirthFromEvent(20L, 1L, LocalDate.of(1970, 5, 20));
    }

    @Test
    void create_withNonBirthdayCategory_doesNotSyncRelative() {
        Relative relative = Relative.builder().id(20L).user(User.builder().id(1L).build())
            .groupType(Relative.GroupType.ME).build();
        when(relativeRepo.findByIdAndUserId(20L, 1L)).thenReturn(java.util.Optional.of(relative));

        CreateEventRequest req = baseRequest(null); // categoryId=2L, "Khác"
        req.setRelativeId(20L);

        service.create(1L, req);

        verifyNoInteractions(relativeService);
    }

    @Test
    void update_changingBirthdayEventDate_syncsRelativeDateOfBirth() {
        Relative relative = Relative.builder().id(20L).user(User.builder().id(1L).build())
            .groupType(Relative.GroupType.ME).build();
        User owner = User.builder().id(1L).build();
        Event existing = Event.builder().id(30L).user(owner).category(birthdayCategory())
            .relative(relative).eventDate(LocalDate.of(1970, 5, 20)).build();
        when(eventRepo.findById(30L)).thenReturn(java.util.Optional.of(existing));
        when(relativeRepo.findByIdAndUserId(20L, 1L)).thenReturn(java.util.Optional.of(relative));
        when(categoryRepo.findById(3L)).thenReturn(java.util.Optional.of(birthdayCategory()));

        CreateEventRequest req = baseRequest(null);
        req.setCategoryId(3L);
        req.setRelativeId(20L);
        req.setEventDate(LocalDate.of(1970, 6, 21));

        service.update(30L, 1L, req);

        verify(relativeService).syncDateOfBirthFromEvent(20L, 1L, LocalDate.of(1970, 6, 21));
    }

    // ── CHỐNG TRÙNG EVENT SINH NHẬT — RelativeService.syncBirthdayEvent()
    // đã tự sinh 1 Event "Sinh nhật" khi thêm ngày sinh cho người thân; nếu
    // người dùng lại TỰ TAY tạo thêm 1 Event danh mục Sinh nhật cho ĐÚNG
    // người thân đó qua màn "Tạo sự kiện", EventService.create() trước đây
    // không kiểm tra gì cả -> insert thêm 1 Event tách biệt, dẫn tới 2 Event
    // + 2 bộ nhắc nhở + 2 thông báo trùng lặp cho cùng 1 sinh nhật. ────────

    @Test
    void create_withBirthdayCategoryAndRelativeAlreadyHavingBirthdayEvent_updatesExistingInsteadOfDuplicating() {
        User owner = User.builder().id(1L).build();
        Relative relative = Relative.builder().id(20L).user(owner).groupType(Relative.GroupType.ME).build();
        Event existingBirthdayEvent = Event.builder()
                .id(40L).user(owner).relative(relative).category(birthdayCategory())
                .eventDate(LocalDate.of(2026, 5, 20))
                .reminders(new ArrayList<>())
                .build();

        when(relativeRepo.findByIdAndUserId(20L, 1L)).thenReturn(java.util.Optional.of(relative));
        when(categoryRepo.findById(3L)).thenReturn(java.util.Optional.of(birthdayCategory()));
        when(eventRepo.findFirstByRelativeIdAndCategory_CodeAndIsActiveTrue(20L, "SINH_NHAT"))
                .thenReturn(java.util.Optional.of(existingBirthdayEvent));
        when(eventRepo.findById(40L)).thenReturn(java.util.Optional.of(existingBirthdayEvent));

        CreateEventRequest req = baseRequest(null);
        req.setCategoryId(3L);
        req.setRelativeId(20L);
        req.setEventDate(LocalDate.of(2027, 5, 20));

        service.create(1L, req);

        // Chỉ 1 lần save duy nhất, nhắm thẳng vào Event đã có (id=40) — không
        // insert Event mới.
        verify(eventRepo, times(1)).save(any(Event.class));
        verify(eventRepo).findById(40L);
        assertEquals(LocalDate.of(2027, 5, 20), existingBirthdayEvent.getEventDate());
        // Counter chỉ tăng khi thật sự tạo mới — event này không phải mới.
        verify(userRepo, never()).incrementEventCount(any());
        verify(relativeRepo, never()).incrementEventCount(any());
    }

    @Test
    void update_changingNonBirthdayEvent_doesNotSyncRelative() {
        User owner = User.builder().id(1L).build();
        Event existing = Event.builder().id(31L).user(owner)
            .category(EventCategory.builder().id(2L).code("KHAC").build())
            .eventDate(LocalDate.now()).build();
        when(eventRepo.findById(31L)).thenReturn(java.util.Optional.of(existing));

        service.update(31L, 1L, baseRequest(null));

        verifyNoInteractions(relativeService);
    }
}
