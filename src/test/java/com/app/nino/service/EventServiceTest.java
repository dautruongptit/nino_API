package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.model.dto.request.CreateEventRequest;
import com.app.nino.model.dto.response.EventResponse;
import com.app.nino.model.entity.Event;
import com.app.nino.model.entity.EventCategory;
import com.app.nino.model.entity.EventReminder;
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
}
