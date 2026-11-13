package org.telegram.luckgram.update;

import org.json.JSONObject;

/**
 * Pure logic of the update check (task S5, luckgram-specs#30): parsing {@code GET /v1/version},
 * comparing versions with the server's rule, deciding when to check and what to show. Only java.*
 * and org.json (JVM unit tests); same rules as LuckgramUpdateLogic.swift on iOS.
 */
public final class UpdateLogic {

    private UpdateLogic() {
    }

    /**
     * Check at most once an hour. Was once a day (spec: "启动时（每天最多一次）"), but releases now
     * ship several times a day with the same version name and only a new build, so a day-old answer
     * hid newer builds (a device that checked right after build 345 still knew nothing of 379 22 h
     * later). Same TTL as the remote config; the request is a few hundred bytes.
     */
    public static final long CHECK_INTERVAL_MS = 60 * 60_000L;
    /** After a failed check try again on a later launch, but not more often than this. */
    public static final long RETRY_AFTER_FAILURE_MS = 60 * 60_000L;

    /** What {@code GET /v1/version} said, persisted so the hint survives restarts. */
    public static final class Info {
        public final String latest;
        public final long build;
        public final String min;
        public final String url;
        public final String notesZh;
        public final String notesEn;
        /** specs#123/#124: lowercase hex SHA-256 and byte size of the APK; "" / 0 while the server has not got them. */
        public final String sha256;
        public final long size;

        public Info(String latest, long build, String min, String url, String notesZh, String notesEn) {
            this(latest, build, min, url, notesZh, notesEn, "", 0);
        }

        public Info(String latest, long build, String min, String url, String notesZh, String notesEn, String sha256, long size) {
            this.latest = latest == null ? "" : latest;
            this.build = build;
            this.min = min == null ? "" : min;
            this.url = url == null ? "" : url;
            this.notesZh = notesZh == null ? "" : notesZh;
            this.notesEn = notesEn == null ? "" : notesEn;
            this.sha256 = UpdateDownloadLogic.isSha256(sha256) ? sha256.toLowerCase(java.util.Locale.ROOT) : "";
            this.size = Math.max(0, size);
        }
    }

    /** What the app shows. */
    public static final class State {
        public static final State NONE = new State(false, false, "", "", "", 0, "", 0);

        /** "有新版本 x.y.z · 更新" on top of Settings. */
        public final boolean updateAvailable;
        /** Full-screen "must update" with only the Update button. */
        public final boolean forceUpdate;
        public final String latest;
        public final String url;
        public final String notes;
        /** Build of {@link #latest} (0 = unknown) and what the downloaded package must match (specs#124). */
        public final long build;
        public final String sha256;
        public final long size;

        State(boolean updateAvailable, boolean forceUpdate, String latest, String url, String notes, long build, String sha256, long size) {
            this.updateAvailable = updateAvailable;
            this.forceUpdate = forceUpdate;
            this.latest = latest;
            this.url = url;
            this.notes = notes;
            this.build = build;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    public static boolean shouldCheck(long nowMs, long lastSuccessMs, long lastFailureMs) {
        return shouldCheck(nowMs, lastSuccessMs, lastFailureMs, "", "");
    }

    /**
     * @param checkedBy {@link #appId} of the app that made the last successful check ("" = unknown)
     * @param running   {@link #appId} of the running app
     * A different app than the one that made the last check (installed an update, or an older
     * package) checks right away: the cached answer was judged against another build.
     */
    public static boolean shouldCheck(long nowMs, long lastSuccessMs, long lastFailureMs, String checkedBy, String running) {
        // a recent failure waits first, also after an update: otherwise every foreground would retry
        if (lastFailureMs > 0 && nowMs >= lastFailureMs && nowMs - lastFailureMs < RETRY_AFTER_FAILURE_MS) {
            return false;
        }
        if (lastSuccessMs > 0 && running != null && !running.isEmpty() && !running.equals(checkedBy)) {
            return true;
        }
        // nowMs < lastSuccessMs: the clock went back; check rather than wait for a day that may never come
        return lastSuccessMs <= 0 || nowMs < lastSuccessMs || nowMs - lastSuccessMs >= CHECK_INTERVAL_MS;
    }

    /** "13.0.3+379": identifies the installed package for {@link #shouldCheck}. */
    public static String appId(String version, long build) {
        return (version == null ? "" : version) + "+" + Math.max(0, build);
    }

    /** {@code /v1/version?platform=android&app_version=..[&build=..]}; build 0 = unknown (local build). */
    public static String path(String platform, String appVersion, long build) {
        StringBuilder sb = new StringBuilder("/v1/version?platform=").append(platform);
        if (isVersion(appVersion)) {
            sb.append("&app_version=").append(appVersion);
            if (build > 0) {
                sb.append("&build=").append(build);
            }
        }
        return sb.toString();
    }

    /** Dotted numbers only, 1–4 segments (same as the server and the X-Luckgram-App-Version guard). */
    public static boolean isVersion(String v) {
        return v != null && v.matches("\\d{1,9}(\\.\\d{1,9}){0,3}");
    }

    /**
     * Server rule (README "版本检查"): dotted numbers compared segment by segment, a missing
     * segment counts as 0. Anything that is not a version compares as 0.0.0.
     */
    public static int compareVersions(String a, String b) {
        String[] x = isVersion(a) ? a.split("\\.") : new String[0];
        String[] y = isVersion(b) ? b.split("\\.") : new String[0];
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? Long.parseLong(x[i]) : 0;
            long q = i < y.length ? Long.parseLong(y[i]) : 0;
            if (p != q) {
                return p < q ? -1 : 1;
            }
        }
        return 0;
    }

    /** Equal versions with both builds known compare by build. */
    public static boolean isNewer(String latest, long latestBuild, String current, long currentBuild) {
        int c = compareVersions(latest, current);
        if (c != 0) {
            return c > 0;
        }
        return latestBuild > 0 && currentBuild > 0 && latestBuild > currentBuild;
    }

    public static Info parse(JSONObject j) {
        if (j == null) {
            return null;
        }
        JSONObject notes = j.optJSONObject("notes");
        return new Info(j.optString("latest", ""), j.optLong("build", 0), j.optString("min", ""), j.optString("url", ""),
                notes != null ? notes.optString("zh", "") : "", notes != null ? notes.optString("en", "") : "",
                j.isNull("sha256") ? "" : j.optString("sha256", ""), j.optLong("size", 0));
    }

    public static String serialize(Info info) {
        if (info == null) {
            return "";
        }
        try {
            JSONObject notes = new JSONObject().put("zh", info.notesZh).put("en", info.notesEn);
            return new JSONObject().put("latest", info.latest).put("build", info.build).put("min", info.min)
                    .put("url", info.url).put("notes", notes).put("sha256", info.sha256).put("size", info.size).toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static Info deserialize(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return parse(new JSONObject(s));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Evaluated against the running version every time (not the server's update_available), so a
     * cached answer stops nagging as soon as the user has installed the new version. Nothing is
     * shown without an https download link: a "must update" screen without a way to update would
     * lock the user out.
     */
    public static State evaluate(Info info, String current, long currentBuild, boolean chinese) {
        if (info == null || !info.url.startsWith("https://") || !isVersion(current)) {
            return State.NONE;
        }
        boolean force = isVersion(info.min) && compareVersions(current, info.min) < 0;
        boolean available = isVersion(info.latest) && isNewer(info.latest, info.build, current, currentBuild);
        if (!force && !available) {
            return State.NONE;
        }
        String notes = chinese ? (info.notesZh.isEmpty() ? info.notesEn : info.notesZh) : (info.notesEn.isEmpty() ? info.notesZh : info.notesEn);
        // Below min but latest not newer (misconfigured): still force, the link is the way out.
        boolean latestIsTarget = isVersion(info.latest) && (available || compareVersions(info.latest, info.min) >= 0);
        // sha256/size/build describe the "latest" package; they don't apply when only "min" is usable
        return new State(available || force, force, isVersion(info.latest) ? info.latest : info.min, info.url, notes,
                latestIsTarget ? info.build : 0, latestIsTarget ? info.sha256 : "", latestIsTarget ? info.size : 0);
    }
}
