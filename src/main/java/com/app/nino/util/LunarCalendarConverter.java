package com.app.nino.util;

/**
 * Thuat toan quy doi Duong lich <-> Am lich (Vietnamese Lunar Calendar).
 * Dua tren thuat toan chuan cua Ho Ngoc Duc (amlich.js) — thuat toan
 * duoc su dung rong rai trong cac ung dung lich Viet Nam.
 *
 * Tinh toan dua tren vi tri thien van (diem Sung/Trang moi), hieu chinh
 * theo timezone Viet Nam (GMT+7).
 *
 * Nguon tham khao: https://www.informatik.uni-leipzig.de/~duc/amlich/
 */
public final class LunarCalendarConverter {

    private static final double PI = Math.PI;
    private static final int VN_TIMEZONE = 7; // GMT+7

    private LunarCalendarConverter() {}

    // ── Julian Day <-> Ngay/Thang/Nam Duong lich ──────────────────────────

    private static long jdFromDate(int dd, int mm, int yy) {
        long a = (14 - mm) / 12;
        long y = yy + 4800 - a;
        long m = mm + 12 * a - 3;
        long jd = dd + (153 * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045;
        if (jd < 2299161) {
            jd = dd + (153 * m + 2) / 5 + 365L * y + y / 4 - 32083;
        }
        return jd;
    }

    private static int[] jdToDate(long jd) {
        long a, b, c;
        if (jd > 2299160) {
            a = jd + 32044;
            b = (4 * a + 3) / 146097;
            c = a - (b * 146097) / 4;
        } else {
            b = 0;
            c = jd + 32082;
        }
        long d = (4 * c + 3) / 1461;
        long e = c - (1461 * d) / 4;
        long m = (5 * e + 2) / 153;
        int day = (int) (e - (153 * m + 2) / 5 + 1);
        int month = (int) (m + 3 - 12 * (m / 10));
        int year = (int) (b * 100 + d - 4800 + m / 10);
        return new int[]{day, month, year};
    }

    // ── Vi tri mat troi / mat trang (thien van) ───────────────────────────

    private static double newMoon(int k) {
        double T = k / 1236.85;
        double T2 = T * T;
        double T3 = T2 * T;
        double dr = PI / 180;
        double Jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * T2 - 0.000000155 * T3;
        Jd1 = Jd1 + 0.00033 * Math.sin((166.56 + 132.87 * T - 0.009173 * T2) * dr);
        double M = 359.2242 + 29.10535608 * k - 0.0000333 * T2 - 0.00000347 * T3;
        double Mpr = 306.0253 + 385.81691806 * k + 0.0107306 * T2 + 0.00001236 * T3;
        double F = 21.2964 + 390.67050646 * k - 0.0016528 * T2 - 0.00000239 * T3;
        double C1 = (0.1734 - 0.000393 * T) * Math.sin(M * dr) + 0.0021 * Math.sin(2 * dr * M);
        C1 = C1 - 0.4068 * Math.sin(Mpr * dr) + 0.0161 * Math.sin(dr * 2 * Mpr);
        C1 = C1 - 0.0004 * Math.sin(dr * 3 * Mpr);
        C1 = C1 + 0.0104 * Math.sin(dr * 2 * F) - 0.0051 * Math.sin(dr * (M + Mpr));
        C1 = C1 - 0.0074 * Math.sin(dr * (M - Mpr)) + 0.0004 * Math.sin(dr * (2 * F + M));
        C1 = C1 - 0.0004 * Math.sin(dr * (2 * F - M)) - 0.0006 * Math.sin(dr * (2 * F + Mpr));
        C1 = C1 + 0.0010 * Math.sin(dr * (2 * F - Mpr)) + 0.0005 * Math.sin(dr * (2 * Mpr + M));
        double deltaT;
        if (T < -11) {
            deltaT = 0.001 + 0.000839 * T + 0.0002261 * T2 - 0.00000845 * T3 - 0.000000081 * T * T3;
        } else {
            deltaT = -0.000278 + 0.000265 * T + 0.000262 * T2;
        }
        return Jd1 + C1 - deltaT;
    }

    private static double sunLongitude(double jdn) {
        double T = (jdn - 2451545.0) / 36525;
        double T2 = T * T;
        double dr = PI / 180;
        double M = 357.52910 + 35999.05030 * T - 0.0001559 * T2 - 0.00000048 * T * T2;
        double L0 = 280.46645 + 36000.76983 * T + 0.0003032 * T2;
        double DL = (1.914600 - 0.004817 * T - 0.000014 * T2) * Math.sin(dr * M);
        DL = DL + (0.019993 - 0.000101 * T) * Math.sin(dr * 2 * M) + 0.000290 * Math.sin(dr * 3 * M);
        double L = L0 + DL;
        L = L * dr;
        L = L - PI * 2 * Math.floor(L / (PI * 2));
        return L;
    }

    private static int getSunLongitude(long dayNumber, int timeZone) {
        return (int) Math.floor(sunLongitude(dayNumber - 0.5 - timeZone / 24.0) / PI * 6);
    }

    private static long getNewMoonDay(int k, int timeZone) {
        return (long) Math.floor(newMoon(k) + 0.5 + timeZone / 24.0);
    }

    private static int getLunarMonth11(int yy, int timeZone) {
        long off = jdFromDate(31, 12, yy) - 2415021;
        int k = (int) Math.floor(off / 29.530588853);
        long nm = getNewMoonDay(k, timeZone);
        int sunLong = getSunLongitude(nm, timeZone);
        if (sunLong >= 9) {
            nm = getNewMoonDay(k - 1, timeZone);
        }
        return (int) nm;
    }

    private static int getLeapMonthOffset(long a11, int timeZone) {
        int k = (int) Math.floor((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int last = 0;
        int i = 1;
        int arc = getSunLongitude(getNewMoonDay(k + i, timeZone), timeZone);
        do {
            last = arc;
            i++;
            arc = getSunLongitude(getNewMoonDay(k + i, timeZone), timeZone);
        } while (arc != last && i < 14);
        return i - 1;
    }

    // ── API chinh: Duong lich -> Am lich ──────────────────────────────────

    /**
     * @return int[]{lunarDay, lunarMonth, lunarYear, isLeapMonth(0/1)}
     */
    public static int[] solarToLunar(int dd, int mm, int yy) {
        long dayNumber = jdFromDate(dd, mm, yy);
        int k = (int) Math.floor((dayNumber - 2415021.076998695) / 29.530588853);
        long monthStart = getNewMoonDay(k + 1, VN_TIMEZONE);
        if (monthStart > dayNumber) {
            monthStart = getNewMoonDay(k, VN_TIMEZONE);
        }

        long a11 = getLunarMonth11(yy, VN_TIMEZONE);
        long b11 = a11;
        int lunarYear;
        if (a11 >= monthStart) {
            lunarYear = yy;
            a11 = getLunarMonth11(yy - 1, VN_TIMEZONE);
        } else {
            lunarYear = yy + 1;
            b11 = getLunarMonth11(yy + 1, VN_TIMEZONE);
        }

        int lunarDay = (int) (dayNumber - monthStart + 1);
        long diff = (monthStart - a11) / 29;
        int lunarLeap = 0;
        int lunarMonth = (int) diff + 11;

        if (b11 - a11 > 365) {
            int leapMonthDiff = getLeapMonthOffset(a11, VN_TIMEZONE);
            if (diff >= leapMonthDiff) {
                lunarMonth = (int) diff + 10;
                if (diff == leapMonthDiff) {
                    lunarLeap = 1;
                }
            }
        }
        if (lunarMonth > 12) {
            lunarMonth = lunarMonth - 12;
        }
        if (lunarMonth >= 11 && diff < 4) {
            lunarYear -= 1;
        }

        return new int[]{lunarDay, lunarMonth, lunarYear, lunarLeap};
    }

    // ── API nguoc: Am lich -> Duong lich ───────────────────────────────────

    public static int[] lunarToSolar(int lunarDay, int lunarMonth, int lunarYear, int lunarLeap) {
        long a11, b11;
        if (lunarMonth < 11) {
            a11 = getLunarMonth11(lunarYear - 1, VN_TIMEZONE);
            b11 = getLunarMonth11(lunarYear, VN_TIMEZONE);
        } else {
            a11 = getLunarMonth11(lunarYear, VN_TIMEZONE);
            b11 = getLunarMonth11(lunarYear + 1, VN_TIMEZONE);
        }

        int k = (int) Math.floor(0.5 + (a11 - 2415021.076998695) / 29.530588853);
        int off = lunarMonth - 11;
        if (off < 0) off += 12;

        if (b11 - a11 > 365) {
            int leapOff = getLeapMonthOffset(a11, VN_TIMEZONE);
            int leapMonth = leapOff - 2;
            if (leapMonth < 0) leapMonth += 12;
            if (lunarLeap != 0 && lunarMonth != leapMonth) {
                return new int[]{0, 0, 0}; // Thang nhuan khong hop le nam nay
            } else if (lunarLeap != 0 || off >= leapOff) {
                off += 1;
            }
        }

        long monthStart = getNewMoonDay(k + off, VN_TIMEZONE);
        long jd = monthStart + lunarDay - 1;
        int[] date = jdToDate(jd);
        return date; // {day, month, year}
    }
}
