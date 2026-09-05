package com.app.nino.service;

import com.app.nino.exception.BadRequestException;
import com.app.nino.model.dto.response.LunarDateResponse;
import com.app.nino.util.LunarCalendarConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Slf4j
@Service
public class LunarCalendarService {

    private static final String[] CAN = {
        "Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý"
    };
    private static final String[] CHI = {
        "Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi"
    };

    /**
     * Quy doi 1 ngay Duong lich sang Am lich.
     * Dung khi nguoi dung chon ngay Duong tren UI (VD: chon ngay gio),
     * he thong hien thi ngay Am tuong ung ben canh.
     */
    public LunarDateResponse solarToLunar(LocalDate solarDate) {
        if (solarDate == null) {
            throw new BadRequestException("Ngày dương lịch không được để trống");
        }

        int[] lunar = LunarCalendarConverter.solarToLunar(
            solarDate.getDayOfMonth(), solarDate.getMonthValue(), solarDate.getYear());

        int lunarDay = lunar[0];
        int lunarMonth = lunar[1];
        int lunarYear = lunar[2];
        boolean isLeap = lunar[3] == 1;

        String displayText = String.format("%d/%d%s Âm lịch",
            lunarDay, lunarMonth, isLeap ? " (nhuận)" : "");

        log.debug("[Lunar] Solar->Lunar: {} -> {}/{}/{}{}",
            solarDate, lunarDay, lunarMonth, lunarYear, isLeap ? " (nhuan)" : "");

        return LunarDateResponse.builder()
            .solarDay(solarDate.getDayOfMonth())
            .solarMonth(solarDate.getMonthValue())
            .solarYear(solarDate.getYear())
            .lunarDay(lunarDay)
            .lunarMonth(lunarMonth)
            .lunarYear(lunarYear)
            .isLeapMonth(isLeap)
            .displayText(displayText)
            .canChi(getCanChi(lunarYear))
            .build();
    }

    /**
     * Quy doi nguoc: Am lich -> Duong lich.
     * Dung de tinh ngay gio nam sau (vi ngay gio co dinh theo Am lich,
     * moi nam se roi vao 1 ngay Duong lich khac nhau).
     */
    public LocalDate lunarToSolar(int lunarDay, int lunarMonth, int lunarYear, boolean isLeapMonth) {
        int[] solar = LunarCalendarConverter.lunarToSolar(
            lunarDay, lunarMonth, lunarYear, isLeapMonth ? 1 : 0);

        if (solar[0] == 0) {
            log.warn("[Lunar] Lunar->Solar that bai — nam {} khong co thang nhuan {}", lunarYear, lunarMonth);
            throw new BadRequestException(
                "Năm " + lunarYear + " không có tháng nhuận " + lunarMonth + " — vui lòng kiểm tra lại");
        }

        LocalDate result = LocalDate.of(solar[2], solar[1], solar[0]);
        log.debug("[Lunar] Lunar->Solar: {}/{}/{}{} -> {}",
            lunarDay, lunarMonth, lunarYear, isLeapMonth ? " (nhuan)" : "", result);
        return result;
    }

    /**
     * Tinh ngay Duong lich cua "ngay gio" nam TIEP THEO, dua tren
     * ngay Am lich co dinh (lunarDay/lunarMonth) da luu tu truoc.
     *
     * Dung cho nghiep vu: user chon 1 lan ngay gio (VD: 15/4 Am lich),
     * he thong tu tinh ngay Duong tuong ung MOI NAM (vi lich Am va
     * Duong lech nhau, ngay Duong se khac nhau tung nam).
     */
    public LocalDate getNextSolarOccurrence(int lunarDay, int lunarMonth, LocalDate fromDate) {
        int currentYear = fromDate.getYear();

        LocalDate thisYearSolar = lunarToSolar(lunarDay, lunarMonth, currentYear, false);
        if (!thisYearSolar.isBefore(fromDate)) {
            return thisYearSolar;
        }
        // Da qua trong nam nay -> tinh cho nam sau
        return lunarToSolar(lunarDay, lunarMonth, currentYear + 1, false);
    }

    private String getCanChi(int year) {
        int canIndex = (year + 6) % 10;
        int chiIndex = (year + 8) % 12;
        return CAN[canIndex] + " " + CHI[chiIndex];
    }
}
