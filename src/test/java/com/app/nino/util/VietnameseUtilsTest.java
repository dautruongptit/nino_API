package com.app.nino.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VietnameseUtilsTest {

    @Test
    void normalize_stripsVietnameseAccents() {
        assertEquals("du lich", VietnameseUtils.normalize("Du lịch"));
        assertEquals("dam cuoi", VietnameseUtils.normalize("Đám cưới"));
        assertEquals("sinh nhat", VietnameseUtils.normalize("Sinh nhật"));
        assertEquals("ky niem", VietnameseUtils.normalize("Kỷ niệm"));
        assertEquals("le tet", VietnameseUtils.normalize("Lễ Tết"));
        assertEquals("hoa don", VietnameseUtils.normalize("Hóa đơn"));
        assertEquals("mua sam", VietnameseUtils.normalize("Mua sắm"));
    }

    @Test
    void normalize_lowercases() {
        assertEquals("du lich", VietnameseUtils.normalize("DU LICH"));
        assertEquals("du lich", VietnameseUtils.normalize("Du Lich"));
    }

    @Test
    void normalize_handlesEmpty() {
        assertEquals("", VietnameseUtils.normalize(""));
        assertEquals("", VietnameseUtils.normalize(null));
    }

    @Test
    void equalsIgnoreAccent_matchesVariants() {
        assertTrue(VietnameseUtils.equalsIgnoreAccent("Du lịch", "du lich"));
        assertTrue(VietnameseUtils.equalsIgnoreAccent("Du lịch", "DU LỊCH"));
        assertTrue(VietnameseUtils.equalsIgnoreAccent("Đám cưới", "dam cuoi"));
        assertTrue(VietnameseUtils.equalsIgnoreAccent("Sinh nhật", "sinh nhat"));
    }

    @Test
    void equalsIgnoreAccent_rejectsDifferent() {
        assertFalse(VietnameseUtils.equalsIgnoreAccent("Du lịch", "Mua sắm"));
        assertFalse(VietnameseUtils.equalsIgnoreAccent("Sinh nhật", "Kỷ niệm"));
    }
}
