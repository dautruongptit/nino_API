package com.app.nino.model.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventReminderTest {

    @Test
    void computeTriggerTime_withMinutesBefore_subtractsMinutesFromEventDateTime() {
        EventReminder reminder = EventReminder.builder().remindMinutesBefore(30).build();

        LocalDateTime trigger = reminder.computeTriggerTime(
            LocalDate.of(2026, 9, 7), LocalTime.of(17, 45));

        assertEquals(LocalDateTime.of(2026, 9, 7, 17, 15), trigger);
    }
}
