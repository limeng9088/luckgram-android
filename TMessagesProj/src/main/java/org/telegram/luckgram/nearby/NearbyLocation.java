package org.telegram.luckgram.nearby;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Looper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

/**
 * One-shot location for People Nearby (spec 25 item 5): the platform {@link LocationManager} only
 * (no Google Play Services; on API 31+ its own fused provider), coarse permission is enough, and
 * nothing keeps listening after the fix or the last provider's timeout. Never runs in the
 * background.
 */
public final class NearbyLocation {

    public interface Callback {
        /** On the UI thread. {@code location} is null on failure / timeout. */
        void onLocation(Location location);
    }

    private NearbyLocation() {
    }

    /** A cached fix this recent is used as is (the grid is ~1 km anyway). */
    private static final long FRESH_MS = 5 * 60 * 1000;

    public static boolean hasPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isServiceOn(Context ctx) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        try {
            return lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    /** Providers that are switched on, in the order to try (see {@link NearbyLogic#providerChain}). */
    private static java.util.List<String> chain(LocationManager lm) {
        boolean fused = false, network = false, gps = false;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                fused = lm.hasProvider(LocationManager.FUSED_PROVIDER) && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER);
            }
        } catch (Exception ignore) {
        }
        try {
            network = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignore) {
        }
        try {
            gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ignore) {
        }
        return NearbyLogic.providerChain(Build.VERSION.SDK_INT, fused, network, gps);
    }

    /** Newest last-known fix of the given providers not older than {@code maxAgeMs}, or null. */
    @SuppressLint("MissingPermission")
    private static Location lastKnown(LocationManager lm, Iterable<String> providers, long maxAgeMs) {
        Location best = null;
        long now = System.currentTimeMillis();
        for (String p : providers) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && now - l.getTime() <= maxAgeMs && l.getTime() <= now + 60_000
                    && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            } catch (Exception ignore) {
            }
        }
        return best;
    }

    /**
     * One fix: tries the providers of {@link #chain} one after another (each up to
     * {@link #ATTEMPT_TIMEOUT_MS}); a provider that answers null or times out hands over to the
     * next, so a phone without network location still gets GPS. If all fail, a fresh last-known
     * fix is used, else null. Returns a cancel action.
     */
    @SuppressLint("MissingPermission")
    public static Runnable request(Context ctx, Callback callback) {
        final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !hasPermission(ctx)) {
            AndroidUtilities.runOnUIThread(() -> callback.onLocation(null));
            return () -> {};
        }
        final java.util.List<String> providers = chain(lm);
        if (providers.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> callback.onLocation(null));
            return () -> {};
        }
        java.util.ArrayList<String> all = new java.util.ArrayList<>(providers);
        if (!all.contains(LocationManager.NETWORK_PROVIDER)) all.add(LocationManager.NETWORK_PROVIDER);
        final Location cached = lastKnown(lm, all, FRESH_MS);
        if (cached != null) {
            AndroidUtilities.runOnUIThread(() -> callback.onLocation(cached));
            return () -> {};
        }
        Attempt a = new Attempt(ctx, lm, providers, all, callback);
        a.next();
        return a::cancel;
    }

    /** Each provider gets this long before the next one is asked. */
    public static final long ATTEMPT_TIMEOUT_MS = 10_000;

    /** Walks the provider chain on the UI thread. */
    private static final class Attempt {
        private final Context ctx;
        private final LocationManager lm;
        private final java.util.List<String> providers;
        private final java.util.List<String> all;
        private final Callback callback;
        private int index = -1;
        private boolean done;
        private int generation;
        private CancellationSignal signal;
        private LocationListener listener;
        private final Runnable timeout = this::onFailed;

        Attempt(Context ctx, LocationManager lm, java.util.List<String> providers, java.util.List<String> all, Callback callback) {
            this.ctx = ctx;
            this.lm = lm;
            this.providers = providers;
            this.all = all;
            this.callback = callback;
        }

        @SuppressLint("MissingPermission")
        void next() {
            stopCurrent();
            index++;
            if (index >= providers.size()) {
                done = true;
                // Review P2: an old last-known fix is a failure, not a location.
                callback.onLocation(lastKnown(lm, all, NearbyLogic.FALLBACK_MAX_AGE_MS));
                return;
            }
            final int gen = ++generation;
            final String provider = providers.get(index);
            AndroidUtilities.runOnUIThread(timeout, ATTEMPT_TIMEOUT_MS);
            final Callback got = loc -> AndroidUtilities.runOnUIThread(() -> onResult(gen, loc));
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    signal = new CancellationSignal();
                    lm.getCurrentLocation(provider, signal, ctx.getMainExecutor(), got::onLocation);
                } else {
                    listener = new LocationListener() {
                        @Override
                        public void onLocationChanged(Location location) {
                            got.onLocation(location);
                        }

                        @Override
                        public void onStatusChanged(String p, int status, Bundle extras) {
                        }

                        @Override
                        public void onProviderEnabled(String p) {
                        }

                        @Override
                        public void onProviderDisabled(String p) {
                        }
                    };
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
                }
            } catch (Exception e) {
                FileLog.e(e);
                got.onLocation(null);
            }
        }

        private void onResult(int gen, Location loc) {
            if (done || gen != generation) return;
            if (loc == null) {
                onFailed();
                return;
            }
            done = true;
            stopCurrent();
            callback.onLocation(loc);
        }

        /** Null answer or timeout: ask the next provider. */
        private void onFailed() {
            if (done) return;
            next();
        }

        private void stopCurrent() {
            AndroidUtilities.cancelRunOnUIThread(timeout);
            if (signal != null) {
                signal.cancel();
                signal = null;
            }
            if (listener != null) {
                try {
                    lm.removeUpdates(listener);
                } catch (Exception ignore) {
                }
                listener = null;
            }
        }

        void cancel() {
            if (done) return;
            done = true;
            stopCurrent();
        }
    }
}
