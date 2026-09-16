package com.app.nino.scheduler;

import com.app.nino.model.entity.User;
import com.app.nino.repository.UserRepository;
import com.app.nino.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionScheduler {

    private final UserRepository userRepo;
    private final AuthService authService;

    /** Quét 1 lần/ngày lúc 3h sáng — xóa mềm mọi tài khoản đã hết grace period
     *  (User.GRACE_PERIOD_DAYS ngày kể từ deletionRequestedAt) và chưa bị hủy. */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Ho_Chi_Minh")
    public void processScheduledDeletions() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(User.GRACE_PERIOD_DAYS);
        List<User> due = userRepo.findDueForDeletion(cutoff);

        int done = 0;
        for (User user : due) {
            try {
                authService.finalizeAccountDeletion(user.getId());
                done++;
            } catch (Exception e) {
                log.error("[Scheduler] Loi khi xoa mem tai khoan userId={}: {}", user.getId(), e.getMessage());
            }
        }
        if (done > 0) {
            log.info("[Scheduler] Da xoa mem {} tai khoan qua han grace period", done);
        }
    }
}
