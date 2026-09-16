package com.app.nino.scheduler;

import com.app.nino.model.entity.User;
import com.app.nino.repository.UserRepository;
import com.app.nino.service.AuthService;
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
class AccountDeletionSchedulerTest {

    @Mock
    private UserRepository userRepo;
    @Mock
    private AuthService authService;

    @InjectMocks
    private AccountDeletionScheduler scheduler;

    @Test
    void processScheduledDeletions_finalizesEveryDueUser() {
        User u1 = User.builder().id(1L).build();
        User u2 = User.builder().id(2L).build();
        when(userRepo.findDueForDeletion(any())).thenReturn(List.of(u1, u2));

        scheduler.processScheduledDeletions();

        verify(authService).finalizeAccountDeletion(1L);
        verify(authService).finalizeAccountDeletion(2L);
    }

    @Test
    void processScheduledDeletions_oneFailure_doesNotStopOthers() {
        User u1 = User.builder().id(1L).build();
        User u2 = User.builder().id(2L).build();
        when(userRepo.findDueForDeletion(any())).thenReturn(List.of(u1, u2));
        doThrow(new RuntimeException("boom")).when(authService).finalizeAccountDeletion(1L);

        assertDoesNotThrow(() -> scheduler.processScheduledDeletions());

        verify(authService).finalizeAccountDeletion(2L);
    }

    @Test
    void processScheduledDeletions_noneDue_doesNothing() {
        when(userRepo.findDueForDeletion(any())).thenReturn(List.of());

        scheduler.processScheduledDeletions();

        verifyNoInteractions(authService);
    }
}
