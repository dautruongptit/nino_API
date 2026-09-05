-- ============================================================
-- V7_20260524_seed_users.sql
-- Du lieu khoi tao: 2 tai khoan mac dinh
--
--   admin  / Admin@123  → ROLE_ADMIN + ROLE_USER
--   user01 / User@123   → ROLE_USER
--
-- ⚠ CANH BAO: Doi mat khau ngay sau khi deploy len production!
-- ============================================================

-- ── 1. Insert users ──────────────────────────────────────────
INSERT IGNORE INTO `users` (
    `id`, `username`, `full_name`, `email`,
    `password_hash`, `status`, `language`,
    `dark_mode`, `total_events`, `total_relatives`,
    `is_active`, `failed_login_count`, `total_login_count`,
    `created_at`, `updated_at`
) VALUES
(
    1, 'admin', 'Administrator', 'admin@nhacsu.app',
    '$2b$12$h3xEE8xVNa6ZGWYw.nJgq.vj9m2hF9KBCEIsgHoo57wmxox44xSpy',
    'ACT', 'vi', 0, 0, 0, 1, 0, 0, NOW(), NOW()
),
(
    2, 'user01', 'Nguyen Van A', 'user01@nhacsu.app',
    '$2b$12$2HMmTW2cV1t2Oox4GfjbPO4gR2tOjww.m3hqBt4NL0m8lArmB.OWK',
    'ACT', 'vi', 0, 0, 0, 1, 0, 0, NOW(), NOW()
);

-- ── 2. Gan quyen ─────────────────────────────────────────────
INSERT IGNORE INTO `user_roles` (`user_id`, `role_id`) VALUES
    (1, 1),   -- admin  ← ROLE_USER
    (1, 2),   -- admin  ← ROLE_ADMIN
    (2, 1);   -- user01 ← ROLE_USER
