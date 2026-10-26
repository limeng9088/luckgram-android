package org.telegram.luckgram;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.telegram.messenger.FileLog;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps the device token encrypted with a non-exportable AES key in the Android Keystore.
 * Only the ciphertext is written to SharedPreferences. Keystore entries are removed on uninstall.
 */
final class LuckgramSecureStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "luckgram_device_token_key";

    private LuckgramSecureStore() {
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        if (!create) {
            return null;
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return gen.generateKey();
    }

    static synchronized String get(String prefKey) {
        String stored = LuckgramSettings.prefs().getString(prefKey, null);
        if (stored == null) {
            return null;
        }
        try {
            int sep = stored.indexOf(':');
            byte[] iv = Base64.decode(stored.substring(0, sep), Base64.NO_WRAP);
            byte[] ct = Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP);
            SecretKey k = key(false);
            if (k == null) {
                return null;
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FileLog.e("luckgram: secure store decrypt failed");
            return null;
        }
    }

    static synchronized void set(String prefKey, String value) {
        if (value == null) {
            LuckgramSettings.prefs().edit().remove(prefKey).apply();
            return;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String v = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
            LuckgramSettings.prefs().edit().putString(prefKey, v).apply();
        } catch (Exception e) {
            // Without the Keystore nothing is persisted; the device simply registers again next time.
            FileLog.e("luckgram: secure store encrypt failed");
        }
    }

    static synchronized void deleteKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception ignore) {
        }
    }
}
