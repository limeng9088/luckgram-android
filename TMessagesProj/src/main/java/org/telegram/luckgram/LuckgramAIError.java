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
    // Codes produced by the client itself
    public static final String NETWORK_ERROR = "network_error";
    /** The user declined (or has not given) consent; nothing was sent. */
    public static final String CONSENT_DENIED = "consent_denied";
    public static final String CANCELLED = "cancelled";

    public final String code;
    public final int httpStatus;

    public LuckgramAIError(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /** User-facing message in the current language. */
    public String getLocalizedMessage() {
        return LuckgramStrings.errorMessage(code);
    }

    @Override
    public String toString() {
        return "LuckgramAIError(" + code + ", " + httpStatus + ")";
    }
}
