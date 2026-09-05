-- ============================================================
-- V8_20260722_add_google_auth.sql
-- Them cot ho tro dang nhap/dang ky bang Google
-- ============================================================

ALTER TABLE `users`
    ADD COLUMN `google_id`     VARCHAR(100) DEFAULT NULL AFTER `password_hash`,
    ADD COLUMN `auth_provider` VARCHAR(10)  NOT NULL DEFAULT 'LOCAL'
        COMMENT 'LOCAL=email/password, GOOGLE=Google OAuth2' AFTER `google_id`;

ALTER TABLE `users`
    ADD UNIQUE KEY `idx_users_google_id` (`google_id`);

-- password_hash cho phep NULL doi voi user dang ky bang Google
ALTER TABLE `users`
    MODIFY COLUMN `password_hash` VARCHAR(255) NULL;

ALTER TABLE `users`
    ADD CONSTRAINT `chk_auth_provider`
        CHECK (`auth_provider` IN ('LOCAL','GOOGLE'));
