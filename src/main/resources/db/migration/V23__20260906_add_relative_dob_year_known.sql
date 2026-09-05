-- ============================================================
-- V23_20260906_add_relative_dob_year_known.sql
-- Cho phep them nguoi than chi voi Thang/Ngay sinh, khong bat buoc Nam
-- (nguoi dung co the khong nho ro nam sinh cua nguoi than) — them co
-- date_of_birth_year_known: TRUE = nam trong date_of_birth la nam that,
-- FALSE = date_of_birth chi dung de giu Thang/Ngay, nam la gia tri dai
-- dien (mobile gui 1900), khong dung de tinh tuoi.
-- Du lieu cu mac dinh TRUE (da biet ro nam sinh).
-- ============================================================

ALTER TABLE `relatives`
    ADD COLUMN `date_of_birth_year_known` TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '0 = date_of_birth chi co Thang/Ngay dang tin cay, nam la gia tri dai dien'
        AFTER `date_of_birth`;
