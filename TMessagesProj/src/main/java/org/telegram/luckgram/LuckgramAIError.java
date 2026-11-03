package org.telegram.luckgram;

/** Error returned by {@link LuckgramAI}. {@link #code} is the API server's error code. */
public final class LuckgramAIError {

    // Codes sent by the API server (docs/api-server.md, specs/server/S2, S3)
    public static final String QUOTA_EXCEEDED = "quota_exceeded";
    public static final String CONTENT_REJECTED = "content_rejected";
    public static final String UPSTREAM_UNAVAILABLE = "upstream_unavailable";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String INPUT_TOO_LARGE = "input_too_large";
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String INVALID_TOKEN = "invalid_token";
    /** S12: a Pro-only request (e.g. the premium model) from a free user. */
    public static final String NOT_PRO = "not_pro";
    /** S12: the month's AI credits are used up. */
    public static final String CREDITS_EXHAUSTED = "credits_exhausted";
    // Codes produced by the client itself
    public static final String NETWORK_ERROR = "network_error";
    /** The user declined (or has not given) consent; nothing was sent. */
    public static final String CONSENT_DENIED = "consent_denied";
    public static final String CANCELLED = "cancelled";
    /**
     * Telegram refused a request made before the AI call (specs#72), e.g. loading history for a
     * summary; {@link #telegramCode} and {@link #telegramText} say why.
     */
    public static final String TELEGRAM_ERROR = "telegram_error";

    public final String code;
    public final int httpStatus;
    /** From {@code error.retry_after_seconds} (or the Retry-After header) on 429 responses; 0 if absent. */
    public long retryAfterSeconds;
    /** For {@link #TELEGRAM_ERROR}: Telegram's error code and text (e.g. 400, CHANNEL_PRIVATE). */
    public int telegramCode;
    public String telegramText;

    public LuckgramAIError(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * The error for a failed Telegram request: {@link #TELEGRAM_ERROR} when Telegram answered with an
     * error, {@link #NETWORK_ERROR} only when no answer came (specs#72).
     */
    public static LuckgramAIError fromTelegram(int code, String text) {
        if (org.telegram.luckgram.diag.TelegramErrorKind.of(code, text) == org.telegram.luckgram.diag.TelegramErrorKind.NETWORK) {
            return new LuckgramAIError(NETWORK_ERROR, 0);
        }
        LuckgramAIError e = new LuckgramAIError(TELEGRAM_ERROR, 0);
        e.telegramCode = code;
        e.telegramText = text;
        return e;
    }

    /** User-facing message in the current language. */
    public String getLocalizedMessage() {
        if (TELEGRAM_ERROR.equals(code)) {
            return LuckgramStrings.telegramErrorMessage(telegramCode, telegramText);
        }
        return LuckgramStrings.errorMessage(code);
    }

    @Override
    public String toString() {
        return "LuckgramAIError(" + code + ", " + httpStatus + ")";
    }
}
