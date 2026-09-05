-- ============================================================
-- V12_20260807_seed_event_participants.sql
-- Bo sung vi du thuc te: 1 su kien gan NHIEU nguoi than
-- Chay SAU V10 (seed data) va V11 (tao bang event_participants)
-- ============================================================

-- "Hop phu huynh" (event id=7, hien relative_id=4 la Con trai)
-- Bo sung: ca Me va Bo cung tham gia hop

INSERT IGNORE INTO `event_participants` (`event_id`, `relative_id`) VALUES
    (7, 1),   -- Hop phu huynh: Me tham gia
    (7, 2);   -- Hop phu huynh: Bo tham gia

-- Luu y: relative_id=4 (Con trai) van giu nguyen trong bang `events`
-- (la nguoi lien quan chinh — con di hop), con Me/Bo trong
-- event_participants la nguoi THAM GIA cung, giup UI hien thi
-- "Hop phu huynh — Con trai · voi Me, Bo"
