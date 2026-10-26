/*
 * Luckgram — private notes (spec 05).
 * Licensed under GNU GPL v. 2 or later.
 */

package org.telegram.luckgram.notes;

import java.util.Locale;

/**
 * Text rules of private notes, free of android.* / org.telegram.* so they run as plain JVM unit
 * tests (TMessagesProj/src/test/java/org/telegram/luckgram/notes/UserNotesTextTest.java).
 * Same rules as iOS {@code LuckgramUserNotesText}.
 */
public final class UserNotesText {

    public static final int MAX_LENGTH = 2000;

    private UserNotesText() {
    }

    /** Text as stored: trimmed and cut to {@link #MAX_LENGTH} code points. Empty means "no note". */
    public static String normalize(String note) {
        String value = note == null ? "" : note.trim();
        if (value.codePointCount(0, value.length()) > MAX_LENGTH) {
            value = value.substring(0, value.offsetByCodePoints(0, MAX_LENGTH));
        }
        return value;
    }

    /** First non-empty line, trimmed; shown in the collapsed chat bar and in "All notes". */
    public static String firstLine(String note) {
        if (note == null) {
            return "";
        }
        for (String line : note.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    private static String fold(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** "All notes" search: an empty query matches everything; otherwise the note text or the name contains it. */
    public static boolean matches(String query, String note, String name) {
        String q = fold(query == null ? "" : query.trim());
        if (q.isEmpty()) {
            return true;
        }
        return fold(note).contains(q) || fold(name).contains(q);
    }

    /** Line shown under the name in "All notes": the first line containing the query, else the first line. */
    public static String snippet(String note, String query) {
        String q = fold(query == null ? "" : query.trim());
        if (!q.isEmpty() && note != null) {
            for (String line : note.split("\n")) {
                if (fold(line).contains(q)) {
                    return line.trim();
                }
            }
        }
        return firstLine(note);
    }
}
