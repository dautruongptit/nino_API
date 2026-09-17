package com.app.nino.scheduler;

import com.app.nino.model.entity.Event;
import com.app.nino.model.entity.EventReminder;
import com.app.nino.model.entity.User;
import com.app.nino.repository.EventRepository;
import com.app.nino.repository.NotificationRepository;
import com.app.nino.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HolidayReminderCleanupSchedulerTest {

    @Mock
    private EventRepository eventRepo;

    @Mock
    private NotificationRepository notificationRepo;

    @Mock
    private UserRepository userRepo;

    @InjectMocks
    private HolidayReminderCleanupScheduler scheduler;

    private static Event eventOf(Long id, Long userId) {
        return Event.builder().id(id).user(User.builder().id(userId).build()).build();
    }

    @Test
    void cleanupExpiredHolidayReminders_deletesEveryExpiredEvent() {
        Event e1 = eventOf(1L, 10L);
        Event e2 = eventOf(2L, 20L);
        when(eventRepo.findExpiredHolidayReminders(any())).thenReturn(List.of(e1, e2));

        scheduler.cleanupExpiredHolidayReminders();

        verify(eventRepo).delete(e1);
        verify(eventRepo).delete(e2);
    }

    @Test
    void cleanupExpiredHolidayReminders_oneFailure_doesNotStopOthers() {
        Event e1 = eventOf(1L, 10L);
        Event e2 = eventOf(2L, 20L);
        when(eventRepo.findExpiredHolidayReminders(any())).thenReturn(List.of(e1, e2));
        doThrow(new RuntimeException("boom")).when(eventRepo).delete(e1);

        assertDoesNotThrow(() -> scheduler.cleanupExpiredHolidayReminders());

        verify(eventRepo).delete(e2);
    }

    @Test
    void cleanupExpiredHolidayReminders_noneExpired_doesNothing() {
        when(eventRepo.findExpiredHolidayReminders(any())).thenReturn(List.of());

        scheduler.cleanupExpiredHolidayReminders();

        verify(eventRepo, never()).delete(any());
    }

    @Test
    void cleanupExpiredHolidayReminders_detachesRemindersAndDecrementsOnlyWhenActive() {
        EventReminder reminder = EventReminder.builder().id(100L).build();
        Event active = Event.builder().id(1L).user(User.builder().id(10L).build())
                .isActive(true).reminders(new java.util.ArrayList<>(List.of(reminder))).build();
        Event alreadyInactive = Event.builder().id(2L).user(User.builder().id(20L).build())
                .isActive(false).reminders(new java.util.ArrayList<>()).build();
        when(eventRepo.findExpiredHolidayReminders(any())).thenReturn(List.of(active, alreadyInactive));

        scheduler.cleanupExpiredHolidayReminders();

        // detachReminders called before delete, for the event that has reminders
        var inOrder = inOrder(notificationRepo, eventRepo);
        inOrder.verify(notificationRepo).detachReminders(eq(List.of(100L)));
        inOrder.verify(eventRepo).delete(active);

        // only the already-active event gets its counter decremented — the
        // already-soft-deleted one was decremented once already by
        // EventService.delete(), decrementing again would double-count.
        verify(userRepo).decrementEventCount(10L);
        verify(userRepo, never()).decrementEventCount(20L);
        // alreadyInactive has no reminders — detachReminders is only ever
        // called once, for `active`.
        verify(notificationRepo, times(1)).detachReminders(any());
    }
}
