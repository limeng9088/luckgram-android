package org.telegram.luckgram.backup;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Luckgram 32: cloud backup wire format (S15 {@code /v1/backup}) and the weekly auto-backup rule.
 * The server only ever sees the encrypted 10 backup file (base64) plus a few counters; the password
 * never leaves the device. Same rules as iOS (LuckgramCloudBackupLogic.swift). Pure, unit tested.
 */
public final class CloudBackupLogic {

    public static final String PATH = "/v1/backup";
    public static final int FORMAT_VERSION = 1;
    public static final long AUTO_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;

    private CloudBackupLogic() {
    }

    /** Latest cloud backup as reported by {@code GET /v1/backup}. */
    public static final class Meta {
        public final int accounts;
        /** Epoch millis; 0 when unknown. */
        public final long createdAt;
        /** Base64 of the encrypted file; null when the response only carries metadata. */
        public final String data;

        public Meta(int accounts, long createdAt, String data) {
            this.accounts = accounts;
            this.createdAt = createdAt;
            this.data = data;
        }
    }

    /** {@code PUT /v1/backup} body. {@code dataBase64} is the encrypted backup file. */
    public static JSONObject uploadBody(String dataBase64, int accounts, long createdAtMillis) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("data", dataBase64);
        body.put("accounts", accounts);
        body.put("created_at", rfc3339(createdAtMillis));
        body.put("format_version", FORMAT_VERSION);
        return body;
    }

    /** Parses {@code {"backup":{...}}} (or the bare object); null when there is no backup. */
    public static Meta parseMeta(JSONObject response) {
        if (response == null) {
            return null;
        }
        JSONObject backup = response.optJSONObject("backup");
        if (backup == null) {
            if (response.has("backup") || !response.has("accounts")) {
                return null;
            }
            backup = response;
        }
        String data = backup.optString("data", "");
        return new Meta(
                Math.max(0, backup.optInt("accounts", 0)),
                parseRfc3339(backup.optString("created_at", backup.optString("uploaded_at", ""))),
                data.isEmpty() ? null : data);
    }

    /** "No backup yet" from {@code GET /v1/backup}. */
    public static boolean isNotFound(String code, int httpStatus) {
        return httpStatus == 404 || "not_found".equals(code);
    }

    /** Weekly auto backup: due when it is on, the password is remembered and 7 days have passed. */
    public static boolean autoBackupDue(boolean enabled, boolean passwordRemembered, long lastMillis, long nowMillis) {
        if (!enabled || !passwordRemembered) {
            return false;
        }
        return lastMillis <= 0 || nowMillis - lastMillis >= AUTO_INTERVAL_MS || nowMillis < lastMillis;
    }

    public static String rfc3339(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    public static long parseRfc3339(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                format.setLenient(false);
                Date date = format.parse(value);
                if (date != null) {
                    return date.getTime();
                }
            } catch (ParseException | IllegalArgumentException ignored) {
            }
        }
        return 0;
    }
}
