package org.telegram.luckgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Settings → Luckgram Features. Generated from {@link LuckgramFeatures}; adding a feature
 * never requires changes here.
 */
public class LuckgramSettingsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_FEATURE_BASE = 1000;
    private static final int ID_AI_CONSENT = 10;
    private static final int ID_PLACEHOLDER_BASE = 20;
    private static final int ID_DEBUG_MOCK = 30;
    private static final int ID_DEBUG_SELFTEST = 31;
    private static final int ID_DEBUG_RESET_TOKEN = 32;
    private static final int ID_DEBUG_CONSENT_CHECK = 33;

    private TextView footerView;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.luckgramSettingsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.luckgramSettingsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.luckgramSettingsChanged && listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public View createView(Context context) {
        footerView = new TextView(context);
        footerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        footerView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        footerView.setGravity(Gravity.CENTER);
        footerView.setPadding(dp(21), dp(14), dp(21), dp(24));
        footerView.setText(LuckgramStrings.footer(versionString()));
        return super.createView(context);
    }

    @Override
    protected CharSequence getTitle() {
        return LuckgramStrings.featuresTitle();
    }

    private static String versionString() {
        try {
            PackageInfo pi = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return pi.versionName + " (" + (pi.versionCode / 10) + ")";
        } catch (Exception e) {
            return BuildVars.BUILD_VERSION_STRING;
        }
    }

    /** Groups that are shown on the page, in fixed order. */
    private static final LuckgramFeature.Group[] PAGE_GROUPS = {
        LuckgramFeature.Group.ACCOUNT_PRIVACY,
        LuckgramFeature.Group.MESSAGES_LANGUAGE,
        LuckgramFeature.Group.MEDIA_CALLS_STORAGE,
        LuckgramFeature.Group.PROFILE_ORGANIZE,
        LuckgramFeature.Group.AI,
    };

    private static List<LuckgramFeature> visibleFeatures(LuckgramFeature.Group group) {
        ArrayList<LuckgramFeature> out = new ArrayList<>();
        for (LuckgramFeature f : LuckgramFeatures.all()) {
            if (f.group == group && f.isVisible() && LuckgramSettings.isRemotelyAllowed(f.key)) {
                out.add(f);
            }
        }
        return out;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        List<LuckgramFeature> all = LuckgramFeatures.all();
        for (LuckgramFeature.Group group : PAGE_GROUPS) {
            items.add(UItem.asHeader(LuckgramStrings.group(group)));
            List<LuckgramFeature> features = visibleFeatures(group);
            for (LuckgramFeature f : features) {
                items.add(LuckgramFeatureCell.Factory.of(ID_FEATURE_BASE + all.indexOf(f), f));
            }
            if (group == LuckgramFeature.Group.AI) {
                items.add(UItem.asButton(ID_AI_CONSENT, LuckgramStrings.aiConsentRow(),
                    LuckgramAI.getInstance().hasConsent() ? LuckgramStrings.get("已同意", "Allowed") : LuckgramStrings.get("未同意", "Not allowed")));
                items.add(UItem.asShadow(LuckgramAI.getInstance().hasConsent() ? LuckgramStrings.aiConsentGranted() : LuckgramStrings.aiConsentNotGranted()));
            } else {
                if (features.isEmpty()) {
                    items.add(UItem.asButton(ID_PLACEHOLDER_BASE + group.ordinal(), LuckgramStrings.comingSoon()).setEnabled(false));
                }
                items.add(UItem.asShadow(null));
            }
        }

        if (LuckgramSettings.isDebugToolsVisible()) {
            items.add(UItem.asHeader(LuckgramStrings.group(LuckgramFeature.Group.DEBUG)));
            for (LuckgramFeature f : visibleFeatures(LuckgramFeature.Group.DEBUG)) {
                items.add(LuckgramFeatureCell.Factory.of(ID_FEATURE_BASE + all.indexOf(f), f));
            }
            items.add(UItem.asCheck(ID_DEBUG_MOCK, LuckgramStrings.get("AI 使用本地 mock", "AI: use local mock")).setChecked(LuckgramAI.getInstance().isUsingMock()));
            items.add(UItem.asButton(ID_DEBUG_SELFTEST, LuckgramStrings.get("AI 自检：注册、流式对话、转写、额度", "AI self-test: register, stream, transcribe, quota")));
            items.add(UItem.asButton(ID_DEBUG_CONSENT_CHECK, LuckgramStrings.get("AI 自检：拒绝同意时不发请求", "AI self-test: no request without consent")));
            items.add(UItem.asButton(ID_DEBUG_RESET_TOKEN, LuckgramStrings.get("清除设备令牌", "Clear device token")));
            items.add(UItem.asShadow(LuckgramStrings.get("服务器：", "Server: ") + (LuckgramAI.getInstance().isUsingMock() ? "mock" : LuckgramAI.apiBaseUrl())));
        }

        items.add(UItem.asCustomShadow(footerView));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof LuckgramFeature) {
            LuckgramFeature f = (LuckgramFeature) item.object;
            boolean onSwitch = f.subpage == null || (LocaleController.isRTL ? x <= dp(76) : x >= view.getMeasuredWidth() - dp(76));
            if (onSwitch) {
                boolean value = !f.isEnabled();
                LuckgramSettings.setEnabled(f.key, value);
                ((NotificationsCheckCell) view).setChecked(value);
            } else {
                presentFragment(f.subpage.run());
            }
            return;
        }
        switch (item.id) {
            case ID_AI_CONSENT:
                if (LuckgramAI.getInstance().hasConsent()) {
                    showDialog(new AlertDialog.Builder(getParentActivity())
                        .setTitle(LuckgramStrings.aiRevokeTitle())
                        .setMessage(LuckgramStrings.aiRevokeText())
                        .setPositiveButton(LuckgramStrings.aiRevoke(), (d, w) -> LuckgramAI.getInstance().revokeConsent())
                        .setNegativeButton(LuckgramStrings.cancel(), null)
                        .create());
                } else {
                    LuckgramAI.getInstance().requestConsent(getParentActivity(), granted -> {
                    });
                }
                break;
            case ID_DEBUG_MOCK: {
                boolean v = !LuckgramAI.getInstance().isUsingMock();
                LuckgramAI.getInstance().setUseMock(v);
                ((TextCheckCell) view).setChecked(v);
                break;
            }
            case ID_DEBUG_RESET_TOKEN:
                LuckgramAI.getInstance().resetLocalState();
                toast("OK");
                break;
            case ID_DEBUG_SELFTEST:
                runSelfTest();
                break;
            case ID_DEBUG_CONSENT_CHECK:
                runConsentCheck();
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void toast(String text) {
        try {
            Toast.makeText(getParentActivity(), text, Toast.LENGTH_SHORT).show();
        } catch (Exception ignore) {
        }
    }

    // ---------------------------------------------------------------- debug self-tests

    private TextView logView;
    private final StringBuilder log = new StringBuilder();

    private void openLog(String title) {
        log.setLength(0);
        logView = new TextView(getParentActivity());
        logView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        logView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        logView.setPadding(dp(24), dp(8), dp(24), dp(8));
        showDialog(new AlertDialog.Builder(getParentActivity())
            .setTitle(title)
            .setView(logView)
            .setPositiveButton(LuckgramStrings.ok(), null)
            .create());
    }

    private void line(String s) {
        log.append(s).append('\n');
        if (logView != null) logView.setText(log);
    }

    private void runSelfTest() {
        LuckgramAI ai = LuckgramAI.getInstance();
        openLog("LuckgramAI self-test (" + (ai.isUsingMock() ? "mock" : LuckgramAI.apiBaseUrl()) + ")");
        ai.resetLocalState();
        line("1. register + stream chat …");
        final StringBuilder streamed = new StringBuilder();
        final int[] deltas = {0};
        ai.chat(getParentActivity(), LuckgramAI.TASK_CHAT, Collections.singletonList(LuckgramAI.Message.user("Say hello to Luckgram in one short sentence.")), true, new LuckgramAI.ChatListener() {
            @Override
            public void onDelta(String delta, String text) {
                deltas[0]++;
                streamed.setLength(0);
                streamed.append(text);
                if (logView != null) logView.setText(log + "   » " + text);
            }

            @Override
            public void onComplete(String text) {
                line("   ✓ token=" + (ai.hasDeviceToken() ? "yes" : "no") + ", " + deltas[0] + " deltas: " + text);
                step2();
            }

            @Override
            public void onError(LuckgramAIError error) {
                line("   ✗ " + error.code + " — " + error.getLocalizedMessage());
            }
        });
    }

    private void step2() {
        line("2. transcribe (1 s test tone, WAV) …");
        LuckgramAI.getInstance().transcribe(getParentActivity(), LuckgramAI.testToneWav(1), "audio/wav", null, new LuckgramAI.Callback<LuckgramAI.Transcription>() {
            @Override
            public void onResult(LuckgramAI.Transcription t) {
                line("   ✓ text=\"" + t.text + "\" language=" + t.language);
                step2b();
            }

            @Override
            public void onError(LuckgramAIError error) {
                line("   ✗ " + error.code + " — " + error.getLocalizedMessage());
                step2b();
            }
        });
    }

    /** A 150 s WAV (≈ 4.8 MB) is over ai.transcribe_max_mb = 4 and must be split into 2 segments. */
    private void step2b() {
        byte[] wav = LuckgramAI.testToneWav(150);
        line("2b. transcribe long file (" + wav.length / 1024 + " KB, split) …");
        LuckgramAI.getInstance().transcribe(getParentActivity(), wav, "audio/wav", "zh", new LuckgramAI.Callback<LuckgramAI.Transcription>() {
            @Override
            public void onResult(LuckgramAI.Transcription t) {
                line("   " + (t.segments > 1 && !t.partial ? "✓ " : "✗ ") + t.segments + " segments: \"" + t.text + "\"");
                step2bPartial(wav);
            }

            @Override
            public void onError(LuckgramAIError error) {
                line("   ✗ " + error.code + " — " + error.getLocalizedMessage());
                step2c();
            }
        });
    }

    /** Mock only: segment 2 fails with quota_exceeded; segment 1's text must be kept (#55). */
    private void step2bPartial(byte[] wav) {
        LuckgramAI ai = LuckgramAI.getInstance();
        if (!ai.isUsingMock()) {
            step2c();
            return;
        }
        line("2b'. partial result when segment 2 hits quota (mock) …");
        ai.getMock().failTranscribeAfter(1, LuckgramAIError.QUOTA_EXCEEDED);
        ai.transcribe(getParentActivity(), wav, "audio/wav", null, new LuckgramAI.Callback<LuckgramAI.Transcription>() {
            @Override
            public void onResult(LuckgramAI.Transcription t) {
                boolean ok = t.partial && t.completedSegments == 1 && t.error != null && LuckgramAIError.QUOTA_EXCEEDED.equals(t.error.code) && !t.text.isEmpty();
                line("   " + (ok ? "✓ " : "✗ ") + "partial=" + t.partial + " " + t.completedSegments + "/" + t.segments + " error=" + (t.error == null ? "-" : t.error.code) + " text=\"" + t.text + "\"");
                step2c();
            }

            @Override
            public void onError(LuckgramAIError error) {
                line("   ✗ whole call failed: " + error.code);
                step2c();
            }
        });
    }

    private void step2c() {
        line("2c. image (url + download with Bearer) …");
        LuckgramAI.getInstance().image(getParentActivity(), "a small blue paper plane, flat icon", new LuckgramAI.Callback<LuckgramAI.Image>() {
            @Override
            public void onResult(LuckgramAI.Image img) {
                line("   ✓ " + img.mimeType + ", " + (img.data == null ? 0 : img.data.length) + " bytes, expires " + img.expiresAt);
                step3();
            }

            @Override
            public void onError(LuckgramAIError error) {
                line("   ✗ " + error.code + " — " + error.getLocalizedMessage());
                step3();
            }
        });
    }

    private void step3() {
        line("3. GET /v1/me …");
        LuckgramAI.getInstance().me(getParentActivity(), new LuckgramAI.Callback<JSONObject>() {
            @Override
            public void onResult(JSONObject j) {
                line("   ✓ " + j.toString());
                line(LuckgramStrings.get("完成", "Done"));
            }

            @Override
            public void onError(LuckgramAIError error) {
                line("   ✗ " + error.code + " — " + error.getLocalizedMessage());
            }
        });
    }

    /** Revokes consent, then calls chat with no Context (= user declines) and checks the mock saw no request. */
    private void runConsentCheck() {
        LuckgramAI ai = LuckgramAI.getInstance();
        boolean hadConsent = ai.hasConsent();
        boolean wasMock = ai.isUsingMock();
        ai.setUseMock(true);
        ai.revokeConsent();
        int before = ai.getMock().requestCount();
        openLog("LuckgramAI consent check");
        line("consent revoked; calling chat while declining …");
        ai.chat(null, LuckgramAI.TASK_CHAT, Collections.singletonList(LuckgramAI.Message.user("x")), false, new LuckgramAI.ChatListener() {
            @Override
            public void onDelta(String delta, String text) {
            }

            @Override
            public void onComplete(String text) {
                line("✗ request was sent");
                restore();
            }

            @Override
            public void onError(LuckgramAIError error) {
                int sent = ai.getMock().requestCount() - before;
                line((LuckgramAIError.CONSENT_DENIED.equals(error.code) && sent == 0 ? "✓ " : "✗ ") + error.code + ", requests sent: " + sent);
                restore();
            }

            private void restore() {
                ai.setUseMock(wasMock);
                if (hadConsent) {
                    LuckgramSettings.setBool("ai_consent", true);
                }
                line(LuckgramStrings.get("已恢复原来的同意状态", "Original consent restored"));
            }
        });
    }
}
