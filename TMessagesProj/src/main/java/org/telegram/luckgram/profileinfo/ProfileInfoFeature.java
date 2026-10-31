/*
 * Luckgram — profile details (spec 07).
 * Licensed under GNU GPL v. 2 or later.
 */

package org.telegram.luckgram.profileinfo;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** App glue for spec 07: switch, strings and the bundled registration-date table. */
public final class ProfileInfoFeature {

    /** Registered in {@link org.telegram.luckgram.LuckgramFeatures} (default on). */
    public static final String FEATURE_KEY = "profile_details";

    private static ArrayList<ProfileInfoLogic.Point> table;

    private ProfileInfoFeature() {
    }

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(FEATURE_KEY);
    }

    private static synchronized ArrayList<ProfileInfoLogic.Point> table() {
        if (table == null) {
            String text = null;
            try (InputStream in = ApplicationLoader.applicationContext.getResources().openRawResource(R.raw.luckgram_registration_dates)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                FileLog.e(e);
            }
            table = ProfileInfoLogic.parseTable(text);
        }
        return table;
    }

    /** "约 2019 年 10 月" etc., or null when the row should not be shown. */
    public static String registrationText(long userId) {
        return ProfileInfoLogic.formatEstimate(ProfileInfoLogic.estimate(table(), userId), LuckgramStrings.isChinese());
    }

    public static String idTitle() {
        return "ID";
    }

    public static String idCopied() {
        return LuckgramStrings.get("ID 已复制", "ID copied");
    }

    public static String linkCopied() {
        return LuckgramStrings.get("链接已复制", "Link copied");
    }

    public static String copyId() {
        return LuckgramStrings.get("复制 ID", "Copy ID");
    }

    public static String copyLink() {
        return LuckgramStrings.get("复制链接", "Copy link");
    }

    public static String registeredTitle() {
        return LuckgramStrings.get("注册时间", "Registered");
    }

    public static String registeredInfo() {
        return LuckgramStrings.get("按 ID 估算", "estimated from ID");
    }
}
