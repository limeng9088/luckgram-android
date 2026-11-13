package org.telegram.luckgram.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure rules of the in-app APK download (luckgram-specs#124): file naming, verification, when to
 * download on its own, which old packages to delete and the error texts. Only java.* (JVM tests).
 */
public final class UpdateDownloadLogic {

    private UpdateDownloadLogic() {
    }

    public static final String PREFIX = "luckgram-update-";
    public static final String SUFFIX = ".apk";
    public static final String PART = ".part";
    /** Keep this much free on top of the package itself. */
    public static final long SPACE_MARGIN = 20L * 1024 * 1024;
    /** An automatic download that failed is not retried on its own before this. */
    public static final long AUTO_RETRY_AFTER_MS = 6 * 60 * 60_000L;
    /** Anything bigger is not an APK we published (server limit is 500 MB). */
    public static final long MAX_SIZE = 500L * 1024 * 1024;

    public static boolean isSha256(String s) {
        return s != null && s.matches("[0-9a-fA-F]{64}");
    }

    public static boolean isHttps(String url) {
        return url != null && url.regionMatches(true, 0, "https://", 0, 8) && url.length() > 8;
    }

    /** One file per version + build; only digits, dots and letters so it is a safe name. */
    public static String fileName(String version, long build) {
        String v = version == null ? "" : version.replaceAll("[^0-9A-Za-z.]", "");
        return PREFIX + v + "-" + Math.max(0, build) + SUFFIX;
    }

    public enum Verdict {
        /** sha256 (and size when given) match. */
        OK,
        /** The server sent no sha256: accepted, but written to the diagnostic log. */
        NOT_CHECKED,
        SIZE_MISMATCH,
        SHA_MISMATCH,
    }

    public static Verdict verify(String expectedSha, long expectedSize, String actualSha, long actualSize) {
        if (expectedSize > 0 && expectedSize != actualSize) {
            return Verdict.SIZE_MISMATCH;
        }
        if (!isSha256(expectedSha)) {
            return Verdict.NOT_CHECKED;
        }
        return expectedSha.equalsIgnoreCase(actualSha == null ? "" : actualSha) ? Verdict.OK : Verdict.SHA_MISMATCH;
    }

    public static boolean accepted(Verdict v) {
        return v == Verdict.OK || v == Verdict.NOT_CHECKED;
    }

    public static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** Enough room for the package (unknown size: only the margin). */
    public static boolean enoughSpace(long usable, long size) {
        return usable >= Math.max(0, size) + SPACE_MARGIN;
    }

    /**
     * Download on its own only when there is something new, the switch is on, the network is not
     * metered, nothing is downloaded or downloading already and an earlier automatic try for the
     * same package did not fail recently.
     */
    public static boolean shouldAutoDownload(boolean updateAvailable, String url, boolean switchOn, boolean unmetered,
                                             boolean alreadyReady, boolean downloading, long nowMs, long lastAutoFailureMs) {
        if (!updateAvailable || !isHttps(url) || !switchOn || !unmetered || alreadyReady || downloading) {
            return false;
        }
        return lastAutoFailureMs <= 0 || nowMs < lastAutoFailureMs || nowMs - lastAutoFailureMs >= AUTO_RETRY_AFTER_MS;
    }

    /**
     * Files of the update folder to delete: every package and partial download, except the one
     * for {@code keepName} (the version still on offer; null when there is no update, e.g. the
     * new version is installed now).
     */
    public static List<String> toDelete(String[] names, String keepName) {
        List<String> out = new ArrayList<>();
        if (names == null) {
            return out;
        }
        for (String n : names) {
            if (n == null || !n.startsWith(PREFIX)) {
                continue;
            }
            if (keepName != null && (n.equals(keepName) || n.equals(keepName + PART))) {
                continue;
            }
            out.add(n);
        }
        return out;
    }

    public enum Failure {
        NETWORK,
        VERIFY,
        SPACE,
        NOT_HTTPS,
    }

    /** Bilingual texts (the app picks one with LuckgramStrings.isChinese()). */
    public static final class Texts {
        private final boolean zh;

        public Texts(boolean zh) {
            this.zh = zh;
        }

        private String t(String c, String e) {
            return zh ? c : e;
        }

        public String failure(Failure f, String detail) {
            switch (f) {
                case VERIFY:
                    return t("安装包校验失败，已删除。请稍后重试。", "The package failed verification and was deleted. Please try again later.");
                case SPACE:
                    return t("存储空间不足，请清理后重试。", "Not enough storage space. Free some space and try again.");
                case NOT_HTTPS:
                    return t("下载地址无效。", "The download link is not valid.");
                default:
                    return t("网络错误，下载失败", "Network error, download failed") + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）");
            }
        }

        public String failedTitle() { return t("下载失败", "Download failed"); }
        public String verifyFailedTitle() { return t("安装包校验失败", "Package verification failed"); }
        /**
         * "13.0.3（379）" / "13.0.3 (379)": releases often keep the version name and only raise the
         * build, so every update text carries the build when it is known (0 = unknown: version only).
         */
        public String label(String v, long build) {
            String name = v == null ? "" : v;
            return build > 0 ? name + t("（" + build + "）", " (" + build + ")") : name;
        }
        public String availableRow(String v, long build) { return t("有新版本 " + label(v, build) + " · 更新", "New version " + label(v, build) + " · Update"); }
        public String readyRow(String v, long build) { return t("新版本 " + label(v, build) + " 已下载 · 安装", "New version " + label(v, build) + " downloaded · Install"); }
        public String downloadingRow(String v, long build, int percent) {
            String l = label(v, build);
            return t("正在下载新版本 " + l + (percent >= 0 ? " · " + percent + "%" : ""), "Downloading version " + l + (percent >= 0 ? " · " + percent + "%" : ""));
        }
        public String downloadingTitle(String v, long build) { return t("正在下载 " + label(v, build), "Downloading " + label(v, build)); }
        public String notesTitle(String v, long build) { return t("新版本 " + label(v, build), "Version " + label(v, build)); }
        /** Force page body: installed version and the one to update to, both with their builds. */
        public String forceText(String current, long currentBuild, String target, long targetBuild) {
            return t("当前版本 " + label(current, currentBuild) + " 已停止支持，请更新到 " + label(target, targetBuild) + " 后继续使用。",
                    "Version " + label(current, currentBuild) + " is no longer supported. Update to " + label(target, targetBuild) + " to continue.");
        }
        public String updateNow() { return t("立即更新", "Update now"); }
        public String later() { return t("以后再说", "Later"); }
        public String cancel() { return t("取消", "Cancel"); }
        public String install() { return t("安装", "Install"); }
        public String retry() { return t("重试", "Retry"); }
        public String ok() { return t("好", "OK"); }
        public String permissionTitle() { return t("需要安装权限", "Permission needed"); }
        public String permissionText() {
            return t("Android 要求为 Luckgram 打开「安装未知应用」权限，才能安装下载好的新版本。授权后回到 Luckgram 会继续安装，最后由你在系统安装界面确认。",
                    "Android needs the \"Install unknown apps\" permission for Luckgram to install the downloaded version. Come back to Luckgram after allowing it and the installation continues; you confirm it on the system screen.");
        }
        public String openSettings() { return t("去授权", "Allow"); }
        public String autoSwitch() { return t("Wi-Fi 下自动下载更新", "Auto-download updates on Wi-Fi"); }
        public String autoSwitchInfo() {
            return t("有新版本时，在 Wi-Fi 等不计流量的网络下自动下载安装包，安装仍需你确认。",
                    "When a new version is out, download it automatically on Wi-Fi or other unmetered networks. Installing still needs your confirmation.");
        }
    }
}
