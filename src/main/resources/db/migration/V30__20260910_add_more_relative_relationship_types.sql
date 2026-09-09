-- ============================================================
-- V30_20260910_add_more_relative_relationship_types.sql
-- Mo rong enum group_type: them 4 gia tri quan he "gop" moi
-- (Co/Di, Chu/Bac/Cau, Chau, Dau/Re) de bu cac quan he ho hang
-- pho bien con thieu trong picker "Quan he voi ban". Giu nguyen
-- toan bo gia tri cu (xem V22) de khong vo du lieu hien co.
-- ============================================================

ALTER TABLE `relatives`
    MODIFY COLUMN `group_type` ENUM(
        'GIA_DINH','VO_CHONG','CON_CAI','BAN_BE','ANH_CHI_EM',
        'BAN_THAN','ONG','BA','BO','ME','CON','NGUOI_YEU','NGUOI_THAN',
        'CO_DI','CHU_BAC_CAU','CHAU','DAU_RE'
    ) NOT NULL;
