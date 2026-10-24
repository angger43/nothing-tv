package com.gathin.tvhome;

import java.util.Calendar;
import java.util.Locale;

/** Small offline solar calculator: no network, polling, account or fabricated schedule. */
final class PrayerTimes {
    static final class City {
        final String name; final double latitude, longitude;
        City(String name, double latitude, double longitude) {
            this.name = name; this.latitude = latitude; this.longitude = longitude;
        }
    }
    static final City[] CITIES = {
            new City("Jakarta", -6.2088, 106.8456), new City("Bandung", -6.9175, 107.6191),
            new City("Surabaya", -7.2575, 112.7521), new City("Yogyakarta", -7.7956, 110.3695),
            new City("Semarang", -6.9667, 110.4167), new City("Medan", 3.5952, 98.6722),
            new City("Makassar", -5.1477, 119.4327), new City("Denpasar", -8.6500, 115.2167)
    };
    static final String[] METHODS = {"Kemenag RI", "Muslim World League", "ISNA", "Egyptian", "Umm al-Qura"};
    static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    final int[] minutes = new int[6];

    static PrayerTimes calculate(Calendar date, City city, int method) {
        PrayerTimes out = new PrayerTimes();
        int y = date.get(Calendar.YEAR), m = date.get(Calendar.MONTH) + 1, d = date.get(Calendar.DAY_OF_MONTH);
        double jd = julian(y, m, d) - city.longitude / (15.0 * 24.0);
        double tz = date.getTimeZone().getOffset(date.getTimeInMillis()) / 3600000.0;
        double noon = midDay(jd);
        double fajrAngle = method == 0 ? 20 : method == 1 ? 18 : method == 2 ? 15 : method == 3 ? 19.5 : 18.5;
        double ishaAngle = method == 0 ? 18 : method == 1 ? 17 : method == 2 ? 15 : method == 3 ? 17.5 : -1;
        double sunrise = sunAngleTime(jd, 0.833, noon, city.latitude, true);
        double sunset = sunAngleTime(jd, 0.833, noon, city.latitude, false);
        double fajr = sunAngleTime(jd, fajrAngle, noon, city.latitude, true);
        double asr = asrTime(jd, 1, noon, city.latitude);
        double isha = method == 4 ? sunset + 1.5 : sunAngleTime(jd, ishaAngle, noon, city.latitude, false);
        double adjust = tz - city.longitude / 15.0;
        double[] raw = {fajr + adjust, sunrise + adjust, noon + adjust + (2.0 / 60.0),
                asr + adjust, sunset + adjust + (2.0 / 60.0), isha + adjust};
        for (int i = 0; i < raw.length; i++) out.minutes[i] = wrapMinutes((int)Math.round(raw[i] * 60));
        return out;
    }

    int nextIndex(Calendar now) {
        int current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (int i = 0; i < minutes.length; i++) if (minutes[i] > current) return i;
        return 0;
    }

    int minutesUntilNext(Calendar now) {
        int current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int next = minutes[nextIndex(now)];
        return next > current ? next - current : 24 * 60 - current + next;
    }

    static String format(int minute, boolean twentyFour) {
        int h = (minute / 60) % 24, m = minute % 60;
        if (twentyFour) return String.format(Locale.US, "%02d:%02d", h, m);
        String ap = h < 12 ? "AM" : "PM";
        int shown = h % 12; if (shown == 0) shown = 12;
        return String.format(Locale.US, "%d:%02d %s", shown, m, ap);
    }

    private static int wrapMinutes(int value) { value %= 1440; return value < 0 ? value + 1440 : value; }
    private static double julian(int y, int m, int d) {
        if (m <= 2) { y--; m += 12; }
        int a = y / 100, b = 2 - a + a / 4;
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }
    private static double[] sunPosition(double jd) {
        double D = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * D);
        double q = fixAngle(280.459 + 0.98564736 * D);
        double L = fixAngle(q + 1.915 * sin(g) + 0.020 * sin(2 * g));
        double e = 23.439 - 0.00000036 * D;
        double decl = asin(sin(e) * sin(L));
        double ra = atan2(cos(e) * sin(L), cos(L)) / 15.0;
        ra = fixHour(ra);
        double eqt = q / 15.0 - ra;
        return new double[]{decl, eqt};
    }
    private static double midDay(double jd) { return 12 - sunPosition(jd + 0.5)[1]; }
    private static double sunAngleTime(double jd, double angle, double noon, double latitude, boolean before) {
        double decl = sunPosition(jd + noon / 24.0)[0];
        double x = (-sin(angle) - sin(decl) * sin(latitude)) / (cos(decl) * cos(latitude));
        x = Math.max(-1, Math.min(1, x));
        double delta = acos(x) / 15.0;
        return noon + (before ? -delta : delta);
    }
    private static double asrTime(double jd, int factor, double noon, double latitude) {
        double decl = sunPosition(jd + noon / 24.0)[0];
        double angle = -acot(factor + Math.tan(rad(Math.abs(latitude - decl))));
        double x = (-sin(angle) - sin(decl) * sin(latitude)) / (cos(decl) * cos(latitude));
        x = Math.max(-1, Math.min(1, x));
        return noon + acos(x) / 15.0;
    }
    private static double fixAngle(double a) { a %= 360; return a < 0 ? a + 360 : a; }
    private static double fixHour(double a) { a %= 24; return a < 0 ? a + 24 : a; }
    private static double rad(double d) { return d * Math.PI / 180; }
    private static double deg(double r) { return r * 180 / Math.PI; }
    private static double sin(double d) { return Math.sin(rad(d)); }
    private static double cos(double d) { return Math.cos(rad(d)); }
    private static double asin(double x) { return deg(Math.asin(x)); }
    private static double acos(double x) { return deg(Math.acos(x)); }
    private static double atan2(double y, double x) { return deg(Math.atan2(y, x)); }
    private static double acot(double x) { return deg(Math.atan2(1, x)); }
}
