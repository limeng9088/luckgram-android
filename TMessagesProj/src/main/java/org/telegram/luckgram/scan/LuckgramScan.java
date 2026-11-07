package org.telegram.luckgram.scan;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;

import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.QrActivity;
import org.telegram.utils.proxy.ProxySettings;

/**
 * specs#92: 「扫一扫」 from the chat list 「+」 menu. Camera permission, Telegram's QR scanner (with
 * 「从相册选图」 and 「我的二维码」), then the scanned code goes to Telegram's own flow: profile /
 * group / channel link, the add-proxy dialog, login on another device, a web link (asks first,
 * in-app browser) or plain text (shown, can be copied).
 */
public final class LuckgramScan {

    /** Camera permission request from the chat list. */
    public static final int REQUEST_CAMERA = 1092;

    public static final ScanLogic.Texts TEXTS = new ScanLogic.Texts(LuckgramStrings.isChinese());

    private LuckgramScan() {
    }

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(ScanLogic.FEATURE_KEY);
    }

    /** specs#118: the next scan (after the camera permission, too) adds a proxy instead. */
    private static boolean proxyMode;

    /** specs#118: scanner from the proxy list; the code goes to {@code ProxyImport.handle}. */
    public static void openForProxy(BaseFragment fragment) {
        proxyMode = true;
        openScanner(fragment);
    }

    /** Opens the scanner, asking for the camera first. */
    public static void open(BaseFragment fragment) {
        proxyMode = false;
        openScanner(fragment);
    }

    private static void openScanner(BaseFragment fragment) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        showScanner(fragment);
    }

    /** From {@code onRequestPermissionsResultFragment}; true if it was ours. */
    public static boolean onPermissionResult(BaseFragment fragment, int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_CAMERA) return false;
        if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showScanner(fragment);
        } else {
            showCameraDenied(fragment);
        }
        return true;
    }

    private static void showCameraDenied(BaseFragment fragment) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        new AlertDialog.Builder(activity)
            .setMessage(TEXTS.cameraDenied())
            .setPositiveButton(TEXTS.openSettings(), (d, i) -> {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + ApplicationLoader.applicationContext.getPackageName()));
                    activity.startActivity(intent);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            })
            .setNegativeButton(TEXTS.notNow(), null)
            .setTopAnimation(R.raw.permission_request_camera, 72, false, Theme.getColor(Theme.key_dialogTopBackground))
            .show();
    }

    private static void showScanner(BaseFragment fragment) {
        CameraScanActivity.showAsSheet(fragment, true, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                // after the sheet closes
                if (proxyMode) {
                    AndroidUtilities.runOnUIThread(() -> org.telegram.luckgram.proxyimport.ProxyImport.handle(fragment, text), 250);
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> handle(fragment, text), 250);
            }

            @Override
            public String getSubtitleText() {
                return proxyMode ? LuckgramStrings.get("扫描代理二维码或订阅地址二维码", "Scan a proxy or subscription QR code") : TEXTS.scanHint();
            }

            @Override
            public String luckgramMyQrText() {
                return proxyMode ? null : TEXTS.myQr();
            }

            @Override
            public void luckgramOpenMyQr() {
                Bundle args = new Bundle();
                args.putLong("user_id", UserConfig.getInstance(fragment.getCurrentAccount()).getClientUserId());
                fragment.presentFragment(new QrActivity(args));
            }
        });
    }

    /** What to do with a scanned code. */
    public static void handle(BaseFragment fragment, String raw) {
        Activity activity = fragment.getParentActivity();
        if (activity == null || raw == null) return;
        String text = raw.trim();
        switch (ScanLogic.kind(text)) {
            case ScanLogic.KIND_LOGIN:
                confirmLogin(fragment, text);
                break;
            case ScanLogic.KIND_PROXY: {
                ProxySettings settings = null;
                try {
                    settings = ProxySettings.fromUri(Uri.parse(ScanLogic.url(text)));
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (settings != null && settings.isValid()) {
                    AndroidUtilities.showProxyAlert(activity, settings);
                } else {
                    showText(fragment, text);
                }
                break;
            }
            case ScanLogic.KIND_TELEGRAM:
                // Telegram's own handling: profile (with 「添加联系人」), group, channel, invite
                Browser.openUrl(activity, ScanLogic.url(text));
                break;
            case ScanLogic.KIND_WEB:
                AlertsCreator.showOpenUrlAlert(fragment, ScanLogic.url(text), true, true);
                break;
            default:
                showText(fragment, text);
        }
    }

    private static void showText(BaseFragment fragment, String text) {
        new AlertDialog.Builder(fragment.getParentActivity())
            .setTitle(TEXTS.text())
            .setMessage(text)
            .setPositiveButton(TEXTS.copy(), (d, i) -> {
                AndroidUtilities.addToClipboard(text);
                BulletinFactory.global().createCopyBulletin(TEXTS.copied()).show();
            })
            .setNegativeButton(TEXTS.cancel(), null)
            .show();
    }

    /** Telegram's 「在其他设备登录」: confirm, then auth.acceptLoginToken (as Settings → Devices does). */
    private static void confirmLogin(BaseFragment fragment, String link) {
        new AlertDialog.Builder(fragment.getParentActivity())
            .setTitle(TEXTS.loginTitle())
            .setMessage(TEXTS.loginConfirm())
            .setPositiveButton(TEXTS.login(), (d, i) -> acceptLogin(fragment, link))
            .setNegativeButton(TEXTS.cancel(), null)
            .show();
    }

    private static void acceptLogin(BaseFragment fragment, String link) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        byte[] token;
        try {
            String code = link.substring(ScanLogic.LOGIN_PREFIX.length()).replaceAll("/", "_").replaceAll("\\+", "-");
            token = Base64.decode(code, Base64.URL_SAFE);
        } catch (Exception e) {
            FileLog.e(e);
            AlertsCreator.showSimpleAlert(fragment, TEXTS.loginTitle(), LocaleController.getString(R.string.ErrorOccurred));
            return;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        TLRPC.TL_auth_acceptLoginToken req = new TLRPC.TL_auth_acceptLoginToken();
        req.token = token;
        ConnectionsManager.getInstance(fragment.getCurrentAccount()).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            try {
                progress.dismiss();
            } catch (Exception ignore) {
            }
            if (response instanceof TLRPC.TL_authorization) {
                BulletinFactory.global().createSimpleBulletin(R.raw.contact_check, TEXTS.loginDone()).show();
            } else if (error != null) {
                String msg = "AUTH_TOKEN_EXCEPTION".equals(error.text)
                    ? LocaleController.getString(R.string.AccountAlreadyLoggedIn)
                    : LocaleController.getString(R.string.ErrorOccurred) + "\n" + error.text;
                AlertsCreator.showSimpleAlert(fragment, TEXTS.loginTitle(), msg);
            }
        }));
    }
}
