package com.app.nino.scheduler;

import com.app.nino.model.entity.Event;
import com.app.nino.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HolidayReminderCleanupSchedulerTest {

    @Mock
    private EventRepository eventRepo;

    @InjectMocks
    private HolidayReminderCleanupScheduler scheduler;

    @Test
    void cleanupExpiredHolidayReminders_deletesEveryExpiredEvent() {
        Event e1 = Event.builder().id(1L).build();
        Event e2 = Event.builder().id(2L).build();
        when(eventRepo.findExpiredHolidayReminders(any())).thenReturn(List.of(e1, e2));

        scheduler.cleanupExpiredHolidayReminders();

        verify(eventRepo).delete(e1);
        verify(eventRepo).delete(e2);
    }

    @Test
    void cleanupExpiredHolidayReminders_oneFailure_doesNotStopOthers() {
        Event e1 = Event.builder().id(1L).build();
        Event e2 = Event.builder().id(2L).build();
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
}
