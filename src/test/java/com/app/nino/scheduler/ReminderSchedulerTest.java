package com.app.nino.scheduler;

import com.app.nino.model.entity.Event;
import com.app.nino.model.entity.EventReminder;
import com.app.nino.model.entity.Notification;
import com.app.nino.model.entity.User;
import com.app.nino.repository.EventReminderRepository;
import com.app.nino.repository.NotificationRepository;
import com.app.nino.service.FcmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReminderSchedulerTest {

    @Mock
    private EventReminderRepository reminderRepo;
    @Mock
    private NotificationRepository notifRepo;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private FcmService fcmService;

    @InjectMocks
    private ReminderScheduler scheduler;

    private User user() {
        return User.builder().id(1L).fullName("Test User").build();
    }

    private Event eventOn(LocalDate date, LocalTime time) {
        return Event.builder()
            .id(9L)
            .user(user())
            .title("Họp nhóm")
            .eventDate(date)
            .eventTime(time)
            .isActive(true)
            .build();
    }

    private Notification notifWithReadStatus(boolean read) {
        return Notification.builder().isRead(read).build();
    }

    // ── isDue: logic quyết định có bắn nhắc nhở hay không ──────────────────

    @Test
    void isDue_returnsTrue_whenTriggerHasPassedAndNeverNotified() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 10, 0);
        LocalDateTime trigger = now.minusMinutes(1);

        assertTrue(ReminderScheduler.isDue(trigger, now, null, null, false));
    }

    @Test
    void isDue_returnsFalse_whenTriggerIsInTheFuture() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 10, 0);
        LocalDateTime trigger = now.plusMinutes(1);

        assertFalse(ReminderScheduler.isDue(trigger, now, null, null, false));
    }

    @Test
    void isDue_returnsFalse_whenAlreadyNotifiedForThisTrigger() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 10, 0);
        LocalDateTime trigger = now.minusMinutes(5);
        LocalDateTime notifiedAt = trigger.plusMinutes(1); // đã gửi ngay sau khi đến hạn

        assertFalse(ReminderScheduler.isDue(trigger, now, notifiedAt, null, false));
    }

    @Test
    void isDue_returnsTrue_whenPreviousNotificationPredatesNewTrigger() {
        // Sự kiện lặp lại (âm lịch): notifiedAt của năm ngoái cũ hơn trigger
        // mới của năm nay -> phải bắn lại.
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 10, 0);
        LocalDateTime trigger = now.minusMinutes(1);
        LocalDateTime notifiedAt = trigger.minusYears(1);

        assertTrue(ReminderScheduler.isDue(trigger, now, notifiedAt, null, false));
    }

    @Test
    void isDue_returnsTrue_whenRepeatIntervalElapsedAndUnread() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 13, 0);
        LocalDateTime trigger = now.minusHours(1);
        LocalDateTime notifiedAt = now.minusMinutes(30); // lần bắn trước cách đây đúng 30p

        assertTrue(ReminderScheduler.isDue(trigger, now, notifiedAt, 30, false));
    }

    @Test
    void isDue_returnsFalse_whenRepeatIntervalNotYetElapsed() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 13, 0);
        LocalDateTime trigger = now.minusHours(1);
        LocalDateTime notifiedAt = now.minusMinutes(10); // mới bắn cách đây 10p, chưa đủ 30p

        assertFalse(ReminderScheduler.isDue(trigger, now, notifiedAt, 30, false));
    }

    @Test
    void isDue_returnsFalse_whenRepeatingReminderAcknowledged() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 13, 0);
        LocalDateTime trigger = now.minusHours(1);
        LocalDateTime notifiedAt = now.minusMinutes(30);

        assertFalse(ReminderScheduler.isDue(trigger, now, notifiedAt, 30, true));
    }

    // ── checkReminders: điều phối toàn bộ luồng ────────────────────────────

    @Test
    void checkReminders_dueReminder_sendsNotificationPushAndMarksNotified() {
        Event event = eventOn(LocalDate.now(), LocalTime.now().minusHours(2));
        EventReminder reminder = EventReminder.builder()
            .id(5L)
            .event(event)
            .remindHoursBefore(1) // trigger = eventTime - 1h = đã qua 1 tiếng trước -> đến hạn
            .isEnabled(true)
            .notifiedAt(null)
            .build();

        when(reminderRepo.findDueCandidates(any())).thenReturn(List.of(reminder));
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache("unreadCount")).thenReturn(cache);

        scheduler.checkReminders();

        verify(notifRepo).save(argThat((Notification n) ->
            n.getUser().getId().equals(1L)
                && n.getEvent().getId().equals(9L)
                && Boolean.FALSE.equals(n.getIsRead())
        ));
        verify(cache).evict(1L);
        verify(fcmService).sendToUser(eq(1L), anyString(), anyString(), any(Map.class));
        verify(reminderRepo).save(argThat((EventReminder r) -> r.getNotifiedAt() != null));
    }

    @Test
    void checkReminders_notYetDueReminder_sendsNothing() {
        Event event = eventOn(LocalDate.now(), LocalTime.now().plusHours(5));
        EventReminder reminder = EventReminder.builder()
            .id(6L)
            .event(event)
            .remindHoursBefore(1) // trigger = eventTime - 1h = còn 4 tiếng nữa mới đến hạn
            .isEnabled(true)
            .notifiedAt(null)
            .build();

        when(reminderRepo.findDueCandidates(any())).thenReturn(List.of(reminder));

        scheduler.checkReminders();

        verify(notifRepo, never()).save(any());
        verify(fcmService, never()).sendToUser(any(), anyString(), anyString(), any());
        verify(reminderRepo, never()).save(any());
    }

    @Test
    void checkReminders_repeatingReminderUnreadAndIntervalElapsed_firesAgain() {
        Event event = eventOn(LocalDate.now(), LocalTime.now().minusHours(2));
        EventReminder reminder = EventReminder.builder()
            .id(7L)
            .event(event)
            .remindHoursBefore(2) // trigger = eventTime - 2h = đúng bây giờ (đã qua từ lâu)
            .repeatIntervalMinutes(30)
            .isEnabled(true)
            .notifiedAt(LocalDateTime.now().minusMinutes(31)) // đã bắn cách đây > 30p
            .build();

        when(reminderRepo.findDueCandidates(any())).thenReturn(List.of(reminder));
        when(notifRepo.findFirstByReminderIdOrderBySentAtDesc(7L))
            .thenReturn(Optional.of(notifWithReadStatus(false)));
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache("unreadCount")).thenReturn(cache);

        scheduler.checkReminders();

        verify(fcmService).sendToUser(eq(1L), anyString(), anyString(), any(Map.class));
        verify(reminderRepo).save(argThat((EventReminder r) -> r.getNotifiedAt() != null));
    }

    @Test
    void checkReminders_repeatingReminderAcknowledged_doesNotFireAgain() {
        Event event = eventOn(LocalDate.now(), LocalTime.now().minusHours(2));
        EventReminder reminder = EventReminder.builder()
            .id(8L)
            .event(event)
            .remindHoursBefore(2)
            .repeatIntervalMinutes(30)
            .isEnabled(true)
            .notifiedAt(LocalDateTime.now().minusMinutes(31))
            .build();

        when(reminderRepo.findDueCandidates(any())).thenReturn(List.of(reminder));
        when(notifRepo.findFirstByReminderIdOrderBySentAtDesc(8L))
            .thenReturn(Optional.of(notifWithReadStatus(true)));

        scheduler.checkReminders();

        verify(fcmService, never()).sendToUser(any(), anyString(), anyString(), any());
        verify(reminderRepo, never()).save(any());
    }
}
