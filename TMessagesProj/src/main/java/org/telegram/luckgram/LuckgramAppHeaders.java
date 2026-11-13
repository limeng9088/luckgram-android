package org.telegram.luckgram;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Version headers on every request to the Luckgram API (specs#164), same as iOS and desktop. The
 * server reads {@code X-Luckgram-App-Build} to decide whether server-side flags such as
 * {@code free_for_all} apply to this build. Values that aren't plain numbers are left out. Pure
 * logic so it can be unit tested.
 */
public final class LuckgramAppHeaders {

    public static final String VERSION_HEADER = "X-Luckgram-App-Version";
    public static final String BUILD_HEADER = "X-Luckgram-App-Build";

    private LuckgramAppHeaders() {
    }

    /**
     * @param versionName versionName, e.g. 13.0.3 (dotted numbers, 1–4 segments)
     * @param ciBuild     CI run number (BuildConfig.LUCKGRAM_BUILD); 0 for local builds
     * @param versionCode installed versionCode (APP_VERSION_CODE*10+abi), used when ciBuild is 0
     * @return the headers, build number as shown in the Settings version line ({@code VersionLine})
     */
    public static Map<String, String> headers(String versionName, int ciBuild, int versionCode) {
        Map<String, String> result = new LinkedHashMap<>();
        String name = versionName == null ? "" : versionName.trim();
        if (name.matches("\\d+(\\.\\d+){0,3}")) {
            result.put(VERSION_HEADER, name);
        }
        int build = ciBuild > 0 ? ciBuild : Math.max(0, versionCode / 10);
        if (build > 0) {
            result.put(BUILD_HEADER, Integer.toString(build));
        }
        return result;
    }
}
