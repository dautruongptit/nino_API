-- ============================================================
-- V10_20260807_seed_sample_data.sql
-- Du lieu mau: 5 nguoi than + 8 su kien cho user01 (id=2)
-- Dung de test app Flutter co du lieu hien thi ngay
-- Idempotent: dung INSERT IGNORE + id co dinh
-- ============================================================

-- ── 1. RELATIVES (5 người — khớp Gia đình:2, Vợ/Chồng:1, Con cái:1, Bạn bè:1) ──

INSERT IGNORE INTO `relatives` (
    `id`, `user_id`, `name`, `nickname`, `group_type`, `gender`,
    `date_of_birth`, `location`, `height_cm`, `weight_kg`,
    `hobbies`, `avatar_url`, `total_events`, `created_at`, `updated_at`
) VALUES
(1, 2, 'Nguyễn Thị Hoa', 'Mẹ',      'GIA_DINH', 'FEMALE',
    '1970-05-15', 'Hà Nội', 158.0, 52.0,
    '["nấu ăn", "làm vườn"]', NULL, 0, NOW(), NOW()),

(2, 2, 'Nguyễn Văn Bình', 'Bố',     'GIA_DINH', 'MALE',
    '1968-08-20', 'Hà Nội', 170.0, 68.0,
    '["đọc báo", "cây cảnh"]', NULL, 0, NOW(), NOW()),

(3, 2, 'Trần Thị Mai', 'Vợ',        'VO_CHONG', 'FEMALE',
    '1992-03-12', 'Hà Nội', 160.0, 50.0,
    '["đọc sách", "yoga"]', NULL, 0, NOW(), NOW()),

(4, 2, 'Nguyễn Gia Bảo', 'Con trai', 'CON_CAI', 'MALE',
    '2018-06-02', 'Hà Nội', 115.0, 20.0,
    '["vẽ tranh", "xếp hình"]', NULL, 0, NOW(), NOW()),

(5, 2, 'Lê Văn Nam', 'Nam',         'BAN_BE', 'MALE',
    '1995-01-10', 'Hồ Chí Minh', 175.0, 70.0,
    '["bóng đá", "du lịch"]', NULL, 0, NOW(), NOW());

-- ── 2. EVENTS ──────────────────────────────────────────────────

-- 2.1 Sự kiện cá nhân (relative_id = NULL)
INSERT IGNORE INTO `events` (
    `id`, `user_id`, `relative_id`, `title`, `event_type`,
    `event_date`, `event_time`, `is_recurring`, `recurrence_type`,
    `notes`, `is_active`, `created_at`, `updated_at`
) VALUES
(1, 2, NULL, 'Đóng tiền phòng', 'NHA_O',
    '2026-04-28', NULL, 1, 'MONTHLY', NULL, 1, NOW(), NOW()),

(2, 2, NULL, 'Nộp tiền điện', 'HOA_DON',
    '2026-04-30', NULL, 1, 'MONTHLY', NULL, 1, NOW(), NOW()),

(3, 2, NULL, 'Mua đồ tặng sinh nhật Lan', 'MUA_SAM',
    '2026-05-13', NULL, 0, NULL, NULL, 1, NOW(), NOW());

-- 2.2 Sự kiện gắn với người thân
INSERT IGNORE INTO `events` (
    `id`, `user_id`, `relative_id`, `title`, `event_type`,
    `event_date`, `event_time`, `is_recurring`, `recurrence_type`,
    `notes`, `is_active`, `created_at`, `updated_at`
) VALUES
(4, 2, 1, 'Sinh nhật Mẹ', 'SINH_NHAT',
    '2026-05-15', NULL, 1, 'YEARLY', 'Đặt bánh kem trước 1 ngày', 1, NOW(), NOW()),

(5, 2, 3, 'Kỷ niệm ngày cưới', 'KY_NIEM',
    '2026-05-20', NULL, 1, 'YEARLY', 'Vợ & Tôi', 1, NOW(), NOW()),

(6, 2, 4, 'Sinh nhật Con', 'SINH_NHAT',
    '2026-06-02', NULL, 1, 'YEARLY', NULL, 1, NOW(), NOW()),

(7, 2, 4, 'Họp phụ huynh', 'KHAC',
    '2026-04-21', '14:00:00', 0, NULL, 'Trường tiểu học', 1, NOW(), NOW()),

(8, 2, 2, 'Sinh nhật Bố', 'SINH_NHAT',
    '2026-08-20', NULL, 1, 'YEARLY', NULL, 1, NOW(), NOW());

-- ── 3. EVENT REMINDERS (nhắc trước 7 ngày + 1 ngày cho các sự kiện quan trọng) ──

INSERT IGNORE INTO `event_reminders` (
    `event_id`, `remind_days_before`, `remind_hours_before`, `is_enabled`, `created_at`
) VALUES
(1, 3, NULL, 1, NOW()),   -- Đóng tiền phòng: nhắc trước 3 ngày
(2, 3, NULL, 1, NOW()),   -- Nộp tiền điện: nhắc trước 3 ngày
(4, 7, NULL, 1, NOW()),   -- Sinh nhật Mẹ: nhắc trước 7 ngày
(4, 1, NULL, 1, NOW()),   -- Sinh nhật Mẹ: nhắc trước 1 ngày
(5, 7, NULL, 1, NOW()),   -- Kỷ niệm cưới: nhắc trước 7 ngày
(5, 1, NULL, 1, NOW()),   -- Kỷ niệm cưới: nhắc trước 1 ngày
(6, 7, NULL, 1, NOW()),   -- Sinh nhật Con: nhắc trước 7 ngày
(6, 1, NULL, 1, NOW()),   -- Sinh nhật Con: nhắc trước 1 ngày
(7, NULL, 3, 1, NOW()),   -- Họp phụ huynh: nhắc trước 3 giờ
(8, 7, NULL, 1, NOW()),   -- Sinh nhật Bố: nhắc trước 7 ngày
(8, 1, NULL, 1, NOW());   -- Sinh nhật Bố: nhắc trước 1 ngày

-- ── 4. CẬP NHẬT CACHE COUNTER (đồng bộ với dữ liệu vừa seed) ──

-- relatives.total_events: đếm số event gắn với từng relative
UPDATE `relatives` r
SET r.total_events = (
    SELECT COUNT(*) FROM `events` e
    WHERE e.relative_id = r.id AND e.is_active = 1
)
WHERE r.id IN (1, 2, 3, 4, 5);

-- users.total_events + users.total_relatives cho user01 (id=2)
UPDATE `users`
SET total_events = (
        SELECT COUNT(*) FROM `events` WHERE user_id = 2 AND is_active = 1
    ),
    total_relatives = (
        SELECT COUNT(*) FROM `relatives` WHERE user_id = 2
    )
WHERE id = 2;
