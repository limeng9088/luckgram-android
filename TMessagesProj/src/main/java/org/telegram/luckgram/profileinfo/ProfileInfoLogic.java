/*
 * Luckgram — profile details (spec 07).
 * Licensed under GNU GPL v. 2 or later.
 */

package org.telegram.luckgram.profileinfo;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Pure rules of spec 07, free of android.* / org.telegram.* so they run as plain JVM tests.
 * Same rules as iOS {@code LuckgramProfileInfoLogic}.
 */
public final class ProfileInfoLogic {

    private ProfileInfoLogic() {
    }

    // ---------------------------------------------------------------- ID

    public static final int PEER_USER = 0;
    public static final int PEER_LEGACY_GROUP = 1;
    /** Channels and supergroups. */
    public static final int PEER_CHANNEL = 2;

    /** ID as shown and copied: users / bots as is, legacy groups {@code -id}, channels / supergroups {@code -100id}. */
    public static String displayId(int peerType, long id) {
        switch (peerType) {
            case PEER_CHANNEL:
                return "-100" + id;
            case PEER_LEGACY_GROUP:
                return "-" + id;
            default:
                return Long.toString(id);
        }
    }

    /** Link for "Copy link": only users / bots have one ({@code tg://user?id=…}); null otherwise. */
    public static String userLink(int peerType, long id) {
        return peerType == PEER_USER ? "tg://user?id=" + id : null;
    }

    // ---------------------------------------------------------------- registration date

    /** Result of {@link #estimate}: {@code kind == BEFORE_2014} → "before 2014"; otherwise year / month (1–12). */
    public static final class Estimate {
        public static final int MONTH = 0;
        public static final int BEFORE_2014 = 1;

        public final int kind;
        public final int year;
        public final int month;

        Estimate(int kind, int year, int month) {
            this.kind = kind;
            this.year = year;
            this.month = month;
        }
    }

    /** One table row: first user id seen at {@code year-month}. Rows are sorted by id and by date. */
    public static final class Point {
        final long id;
        final int monthIndex; // year * 12 + (month - 1)

        Point(long id, int monthIndex) {
            this.id = id;
            this.monthIndex = monthIndex;
        }
    }

    /** Parses the bundled table ("id,YYYY-MM" per line, '#' comments). Malformed or out-of-order lines are skipped. */
    public static ArrayList<Point> parseTable(String text) {
        ArrayList<Point> points = new ArrayList<>();
        if (text == null) {
            return points;
        }
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int comma = line.indexOf(',');
            if (comma <= 0) {
                continue;
            }
            try {
                long id = Long.parseLong(line.substring(0, comma).trim());
                String date = line.substring(comma + 1).trim();
                int year = Integer.parseInt(date.substring(0, 4));
                int month = Integer.parseInt(date.substring(5, 7));
                if (date.charAt(4) != '-' || month < 1 || month > 12) {
                    continue;
                }
                Point p = new Point(id, year * 12 + month - 1);
                if (!points.isEmpty()) {
                    Point last = points.get(points.size() - 1);
                    if (p.id <= last.id || p.monthIndex < last.monthIndex) {
                        continue;
                    }
                }
                points.add(p);
            } catch (RuntimeException ignore) {
                // skip
            }
        }
        return points;
    }

    /**
     * Approximate registration month of a user id by linear interpolation between table rows.
     * Below the first row: "before 2014". Above the last row (newer than the table) or no table:
     * null — the row is not shown rather than guessed.
     */
    public static Estimate estimate(ArrayList<Point> points, long userId) {
        if (points == null || points.size() < 2 || userId <= 0) {
            return null;
        }
        if (userId < points.get(0).id) {
            return new Estimate(Estimate.BEFORE_2014, 0, 0);
        }
        for (int i = 0; i + 1 < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get(i + 1);
            if (userId >= a.id && userId < b.id) {
                double t = (double) (userId - a.id) / (double) (b.id - a.id);
                int m = a.monthIndex + (int) Math.floor(t * (b.monthIndex - a.monthIndex));
                if (m < 2014 * 12) {
                    return new Estimate(Estimate.BEFORE_2014, 0, 0);
                }
                return new Estimate(Estimate.MONTH, m / 12, m % 12 + 1);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- strings (zh / en)

    private static final String[] MONTHS_EN = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** "约 2019 年 10 月" / "about Oct 2019"; "早于 2014 年" / "before 2014". */
    public static String formatEstimate(Estimate e, boolean chinese) {
        if (e == null) {
            return null;
        }
        if (e.kind == Estimate.BEFORE_2014) {
            return chinese ? "早于 2014 年" : "before 2014";
        }
        return chinese
                ? String.format(Locale.ROOT, "约 %d 年 %d 月", e.year, e.month)
                : String.format(Locale.ROOT, "about %s %d", MONTHS_EN[e.month - 1], e.year);
    }
}
