package org.telegram.luckgram.update;

/**
 * 28: the version line at the bottom of Settings, identical on Android and iOS:
 * {@code Luckgram 13.0.3 (189) · 19e5f52}. Pure logic so it can be unit tested.
 */
public final class VersionLine {

    public static final String UNKNOWN = "unknown";

    private VersionLine() {
    }

    /**
     * @param versionName versionName, e.g. 13.0.3
     * @param ciBuild     CI run number (BuildConfig.LUCKGRAM_BUILD); 0 for local builds
     * @param versionCode installed versionCode (APP_VERSION_CODE*10+abi), used when ciBuild is 0
     * @param commit      git commit (BuildConfig.GIT_COMMIT); blank/invalid -> "unknown"
     */
    public static String format(String versionName, int ciBuild, int versionCode, String commit) {
        String name = versionName == null || versionName.trim().isEmpty() ? UNKNOWN : versionName.trim();
        int build = ciBuild > 0 ? ciBuild : Math.max(0, versionCode / 10);
        return "Luckgram " + name + " (" + build + ") · " + shortCommit(commit);
    }

    public static String shortCommit(String commit) {
        if (commit == null) {
            return UNKNOWN;
        }
        String c = commit.trim().toLowerCase(java.util.Locale.ROOT);
        if (!c.matches("[0-9a-f]{7,40}")) {
            return UNKNOWN;
        }
        return c.substring(0, 7);
    }
}
