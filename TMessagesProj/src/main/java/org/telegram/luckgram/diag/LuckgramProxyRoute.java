package org.telegram.luckgram.diag;

/**
 * specs#72: how a Luckgram API request leaves the device, given the in-app Telegram proxy. Only a
 * SOCKS5 proxy can carry HTTP; MTProto and web proxies can't, so those requests go direct and the
 * diagnostic log says so. Pure (no Android types) so it is unit tested.
 */
public final class LuckgramProxyRoute {

    public static final String DIRECT = "direct";
    public static final String SOCKS5 = "socks5";
    public static final String MTPROTO_DIRECT = "mtproto-direct";
    public static final String WEB_DIRECT = "web-direct";

    private LuckgramProxyRoute() {
    }

    /**
     * @param enabled whether the user turned the in-app proxy on
     * @param type    the proxy type name: {@code SOCKS5}, {@code MTPROTO} or {@code WEB}; null if none
     */
    public static String decide(boolean enabled, String type, String address, int port) {
        if (!enabled || type == null) {
            return DIRECT;
        }
        switch (type) {
            case "SOCKS5":
                return address != null && !address.trim().isEmpty() && port > 0 && port < 65536 ? SOCKS5 : DIRECT;
            case "MTPROTO":
                return MTPROTO_DIRECT;
            case "WEB":
                return WEB_DIRECT;
            default:
                return DIRECT;
        }
    }
}
