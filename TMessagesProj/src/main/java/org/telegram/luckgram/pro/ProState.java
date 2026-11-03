package org.telegram.luckgram.pro;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.luckgram.account.AccountLogic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * specs#87 (29): the Luckgram Pro state from {@code GET /v1/me}, as one value. Reads the S12 fields
 * ({@code pro{active,expires_at}}, {@code credits}, {@code perks}, {@code limits}) and falls back to
 * the older {@code tier} / {@code pro_expires_at}; anything missing counts as free. Cached as JSON
 * so Pro still holds offline until {@code expires_at}.
 *
 * <p>Pure Java (java.* and org.json only), unit-tested in {@code ProStateTest}.
 */
public final class ProState {

    // pro.perks values (S12); a perk missing from the list hides its row and feature.
    public static final String PERK_AGENT_MARKET = "agent_market";
    public static final String PERK_ACCOUNTS = "accounts";
    public static final String PERK_AI = "ai";
    public static final String PERK_SUMMARY = "summary";
    public static final String PERK_TRANSLATE = "translate";
    public static final String PERK_COMMUNITY = "community";
    public static final String PERK_NO_ADS = "no_ads";

    /** Defaults when the server sends no {@code limits} (S12 §5). */
    public static final int ACCOUNTS_MAX_FREE = 20;
    public static final int ACCOUNTS_MAX_PRO = 50;
    public static final int SUMMARY_MAX_FREE = 100;
    public static final int SUMMARY_MAX_PRO = 1000;

    /** {@code pro.active} as sent by the server. Use {@link #isPro(long)}. */
    public final boolean active;
    /** 0 = unknown (then {@link #active} alone decides). */
    public final long expiresAtMs;
    public final int creditsRemaining;
    public final int creditsTotal;
    /** 0 = unknown. */
    public final long creditsResetsAtMs;
    public final List<String> perks;
    /** 0 = not sent; use {@link #accountsMax(long)} / {@link #summaryMaxMessages(long)}. */
    private final int accountsMax;
    private final int summaryMaxMessages;
    /** When the server answered; 0 for the free default. */
    public final long fetchedAtMs;

    private ProState(boolean active, long expiresAtMs, int creditsRemaining, int creditsTotal, long creditsResetsAtMs,
                     List<String> perks, int accountsMax, int summaryMaxMessages, long fetchedAtMs) {
        this.active = active;
        this.expiresAtMs = expiresAtMs;
        this.creditsRemaining = Math.max(0, creditsRemaining);
        this.creditsTotal = Math.max(0, creditsTotal);
        this.creditsResetsAtMs = creditsResetsAtMs;
        this.perks = Collections.unmodifiableList(perks);
        this.accountsMax = accountsMax;
        this.summaryMaxMessages = summaryMaxMessages;
        this.fetchedAtMs = fetchedAtMs;
    }

    /** Free, nothing known (no answer yet, or a broken cache). */
    public static ProState free() {
        return new ProState(false, 0, 0, 0, 0, new ArrayList<>(), 0, 0, 0);
    }

    /** Parses a {@code /v1/me} body. Never throws; returns {@link #free()} for null. */
    public static ProState parse(JSONObject me, long nowMs) {
        if (me == null) return free();
        boolean active;
        long expires;
        JSONObject pro = me.optJSONObject("pro");
        if (pro != null) {
            active = pro.optBoolean("active", false);
            expires = AccountLogic.parseTime(optString(pro, "expires_at"));
        } else {
            active = "pro".equals(me.optString("tier", "free"));
            expires = AccountLogic.parseTime(optString(me, "pro_expires_at"));
        }
        int remaining = 0;
        int total = 0;
        long resets = 0;
        JSONObject credits = me.optJSONObject("credits");
        if (credits != null) {
            remaining = credits.optInt("remaining", 0);
            total = credits.optInt("total", 0);
            resets = AccountLogic.parseTime(optString(credits, "resets_at"));
        }
        ArrayList<String> perks = new ArrayList<>();
        JSONArray arr = me.optJSONArray("perks");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String p = arr.optString(i, "").trim();
                if (!p.isEmpty() && !perks.contains(p)) perks.add(p);
            }
        }
        int accounts = 0;
        int summary = 0;
        JSONObject limits = me.optJSONObject("limits");
        if (limits != null) {
            accounts = Math.max(0, limits.optInt("accounts_max", 0));
            summary = Math.max(0, limits.optInt("summary_max_messages", 0));
        }
        return new ProState(active, expires, remaining, total, resets, perks, accounts, summary, nowMs);
    }

    /**
     * Whether Pro applies now: the server said active and, when an expiry is known, it hasn't
     * passed. Offline the cached state keeps working until then.
     */
    public boolean isPro(long nowMs) {
        return active && (expiresAtMs == 0 || nowMs < expiresAtMs);
    }

    public boolean hasPerk(String perk) {
        return perk != null && perks.contains(perk);
    }

    /** AI credits left this period; 0 when not Pro. */
    public int credits(long nowMs) {
        return isPro(nowMs) ? creditsRemaining : 0;
    }

    public int accountsMax(long nowMs) {
        if (accountsMax > 0) return accountsMax;
        return isPro(nowMs) ? ACCOUNTS_MAX_PRO : ACCOUNTS_MAX_FREE;
    }

    public int summaryMaxMessages(long nowMs) {
        if (summaryMaxMessages > 0) return summaryMaxMessages;
        return isPro(nowMs) ? SUMMARY_MAX_PRO : SUMMARY_MAX_FREE;
    }

    /** Whether something a user would see differs (listeners are told only then). */
    public boolean sameAs(ProState o) {
        return o != null && active == o.active && expiresAtMs == o.expiresAtMs && creditsRemaining == o.creditsRemaining
            && creditsTotal == o.creditsTotal && creditsResetsAtMs == o.creditsResetsAtMs && perks.equals(o.perks)
            && accountsMax == o.accountsMax && summaryMaxMessages == o.summaryMaxMessages;
    }

    // ---------------------------------------------------------------- cache

    public String toCache() {
        try {
            JSONObject j = new JSONObject();
            j.put("active", active);
            j.put("expires_at_ms", expiresAtMs);
            j.put("credits_remaining", creditsRemaining);
            j.put("credits_total", creditsTotal);
            j.put("credits_resets_at_ms", creditsResetsAtMs);
            j.put("perks", new JSONArray(perks));
            j.put("accounts_max", accountsMax);
            j.put("summary_max_messages", summaryMaxMessages);
            j.put("fetched_at_ms", fetchedAtMs);
            return j.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads {@link #toCache()}; {@link #free()} for null or anything unreadable. */
    public static ProState fromCache(String s) {
        if (s == null || s.isEmpty()) return free();
        try {
            JSONObject j = new JSONObject(s);
            ArrayList<String> perks = new ArrayList<>();
            JSONArray arr = j.optJSONArray("perks");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String p = arr.optString(i, "");
                    if (!p.isEmpty()) perks.add(p);
                }
            }
            return new ProState(j.optBoolean("active", false), j.optLong("expires_at_ms", 0), j.optInt("credits_remaining", 0),
                j.optInt("credits_total", 0), j.optLong("credits_resets_at_ms", 0), perks, j.optInt("accounts_max", 0),
                j.optInt("summary_max_messages", 0), j.optLong("fetched_at_ms", 0));
        } catch (Exception e) {
            return free();
        }
    }

    private static String optString(JSONObject j, String key) {
        return j.has(key) && !j.isNull(key) ? j.optString(key, null) : null;
    }
}
