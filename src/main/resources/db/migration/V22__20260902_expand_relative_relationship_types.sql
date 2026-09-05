-- ============================================================
-- V22_20260902_expand_relative_relationship_types.sql
-- Mo rong enum group_type: them 8 gia tri quan he cu the moi
-- (theo picker "Quan he voi ban" thiet ke moi). Giu nguyen 5 gia tri
-- cu (GIA_DINH, VO_CHONG, CON_CAI, BAN_BE, ANH_CHI_EM) de khong vo
-- du lieu hien co.
-- ============================================================

ALTER TABLE `relatives`
    MODIFY COLUMN `group_type` ENUM(
        'GIA_DINH','VO_CHONG','CON_CAI','BAN_BE','ANH_CHI_EM',
        'BAN_THAN','ONG','BA','BO','ME','CON','NGUOI_YEU','NGUOI_THAN'
    ) NOT NULL;
