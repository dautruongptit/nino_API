package com.app.nino.model.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserTest {

    @Test
    void getScheduledDeletionAt_returnsNull_whenNoDeletionRequested() {
        User user = User.builder().id(1L).build();

        assertNull(user.getScheduledDeletionAt());
    }

    @Test
    void getScheduledDeletionAt_returnsRequestPlusGracePeriod_whenDeletionRequested() {
        LocalDateTime requestedAt = LocalDateTime.of(2026, 9, 16, 10, 0);
        User user = User.builder().id(1L).deletionRequestedAt(requestedAt).build();

        assertEquals(requestedAt.plusDays(User.GRACE_PERIOD_DAYS), user.getScheduledDeletionAt());
    }
}
