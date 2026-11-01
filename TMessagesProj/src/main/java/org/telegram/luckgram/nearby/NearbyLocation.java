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
 * (no Google Play Services), coarse permission is enough, and nothing keeps listening after the
 * fix or the 15 s timeout. Never runs in the background.
 */
public final class NearbyLocation {

    public interface Callback {
        /** On the UI thread. {@code location} is null on failure / timeout. */
        void onLocation(Location location);
    }

    private NearbyLocation() {
    }

    public static final long TIMEOUT_MS = 15_000;
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

    @SuppressLint("MissingPermission")
    public static Runnable request(Context ctx, Callback callback) {
        final boolean[] done = {false};
        final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !hasPermission(ctx)) {
            AndroidUtilities.runOnUIThread(() -> callback.onLocation(null));
            return () -> {};
        }
        String provider = null;
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
            else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
        } catch (Exception ignore) {
        }
        if (provider == null) {
            AndroidUtilities.runOnUIThread(() -> callback.onLocation(null));
            return () -> {};
        }
        try {
            Location last = lm.getLastKnownLocation(provider);
            if (last != null && System.currentTimeMillis() - last.getTime() < FRESH_MS) {
                AndroidUtilities.runOnUIThread(() -> callback.onLocation(last));
                return () -> {};
            }
        } catch (Exception ignore) {
        }

        final CancellationSignal signal = new CancellationSignal();
        final LocationListener[] listener = {null};
        final Runnable finish = () -> {
            signal.cancel();
            if (listener[0] != null) {
                try {
                    lm.removeUpdates(listener[0]);
                } catch (Exception ignore) {
                }
            }
        };
        final Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            finish.run();
            Location fallback = null;
            try {
                fallback = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (fallback == null) fallback = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            } catch (Exception ignore) {
            }
            callback.onLocation(fallback);
        };
        AndroidUtilities.runOnUIThread(timeout, TIMEOUT_MS);
        final Callback once = loc -> AndroidUtilities.runOnUIThread(() -> {
            if (done[0]) return;
            done[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            finish.run();
            callback.onLocation(loc);
        });
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, signal, ctx.getMainExecutor(), once::onLocation);
            } else {
                listener[0] = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        once.onLocation(location);
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
                lm.requestSingleUpdate(provider, listener[0], Looper.getMainLooper());
            }
        } catch (Exception e) {
            FileLog.e(e);
            once.onLocation(null);
        }
        return () -> {
            if (done[0]) return;
            done[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            finish.run();
        };
    }
}
