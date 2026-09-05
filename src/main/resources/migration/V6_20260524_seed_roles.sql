-- ============================================================
-- V6_20260524_seed_roles.sql
-- Du lieu mac dinh: ROLE_USER, ROLE_ADMIN
-- INSERT IGNORE: idempotent — chay lai khong bi loi
-- ============================================================

INSERT IGNORE INTO `roles` (`id`, `name`, `description`) VALUES
    (1, 'ROLE_USER',  'Nguoi dung thuong - chi truy cap du lieu ca nhan'),
    (2, 'ROLE_ADMIN', 'Quan tri vien - quan ly toan bo he thong');
