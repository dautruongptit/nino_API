-- Thoi diem user bam "Xoa du lieu tai khoan" o security_screen.dart. NULL =
-- khong co yeu cau treo. AccountDeletionScheduler quet cot nay hang ngay,
-- sau User.GRACE_PERIOD_DAYS (14 ngay) ke tu thoi diem nay se xoa mem
-- (anonymize + status=DEL) neu chua bi huy qua endpoint delete-cancel.
ALTER TABLE `users`
    ADD COLUMN `deletion_requested_at` DATETIME NULL COMMENT 'Thoi diem yeu cau xoa tai khoan, NULL = khong co yeu cau treo',
    ADD INDEX `idx_users_deletion_requested_at` (`deletion_requested_at`);
