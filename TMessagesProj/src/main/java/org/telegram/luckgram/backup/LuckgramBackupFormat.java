package org.telegram.luckgram.backup;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Luckgram 10: the {@code .luckgram-backup} file. Identical on iOS ({@code LuckgramBackupFormat.swift});
 * see docs/backup-format.md in luckgram-specs.
 *
 * <pre>
 * file      = UTF-8 JSON {"format":"luckgram-backup","version":1,"kdf":"pbkdf2-sha256",
 *             "iterations":600000,"salt":b64(16),"nonce":b64(12),"ciphertext":b64(ct || tag16)}
 * key       = PBKDF2-HMAC-SHA256(UTF-8 password, salt, iterations, 32 bytes)
 * cipher    = AES-256-GCM, 128-bit tag, AAD = ASCII "luckgram-backup/1"
 * plaintext = UTF-8 JSON {"accounts":[{"user_id":"123","dc_id":2,"auth_key":b64(256),
 *             "server_salt":"-42","name":"Work"}]}
 * </pre>
 * Plain Java (no Android classes) so it runs in JVM unit tests.
 */
public final class LuckgramBackupFormat {

    private LuckgramBackupFormat() {
    }

    public static final String FILE_EXTENSION = "luckgram-backup";
    public static final String FORMAT = "luckgram-backup";
    public static final int VERSION = 1;
    public static final String KDF = "pbkdf2-sha256";
    public static final int ITERATIONS = 600_000;
    public static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_ITERATIONS = 10_000_000;
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "luckgram-backup/1".getBytes(StandardCharsets.US_ASCII);

    /** One account in a backup. {@link #wipe()} clears the key when it is no longer needed. */
    public static final class Account {
        public final long userId;
        public final int dcId;
        public final byte[] authKey;
        public final long serverSalt;
        public final String name;

        public Account(long userId, int dcId, byte[] authKey, long serverSalt, String name) {
            this.userId = userId;
            this.dcId = dcId;
            this.authKey = authKey;
            this.serverSalt = serverSalt;
            this.name = name == null ? "" : name;
        }

        public void wipe() {
            Arrays.fill(authKey, (byte) 0);
        }
    }

    /** The file is not a Luckgram backup, or uses a version this app does not know. */
    public static class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }

    /** Wrong password (or a damaged file: GCM cannot tell them apart). */
    public static class WrongPasswordException extends Exception {
        public WrongPasswordException() {
            super("wrong password");
        }
    }

    // ---------------------------------------------------------------- encode / decode

    public static byte[] encrypt(List<Account> accounts, String password) throws GeneralSecurityException, JSONException {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        return encrypt(accounts, password, salt, nonce, ITERATIONS);
    }

    /** With fixed salt and nonce; only tests use this directly. */
    static byte[] encrypt(List<Account> accounts, String password, byte[] salt, byte[] nonce, int iterations) throws GeneralSecurityException, JSONException {
        byte[] plaintext = plaintext(accounts);
        byte[] key = deriveKey(password, salt, iterations);
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            ciphertext = cipher.doFinal(plaintext);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
        JSONObject file = new JSONObject();
        file.put("format", FORMAT);
        file.put("version", VERSION);
        file.put("kdf", KDF);
        file.put("iterations", iterations);
        file.put("salt", Base64.encode(salt));
        file.put("nonce", Base64.encode(nonce));
        file.put("ciphertext", Base64.encode(ciphertext));
        return file.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static List<Account> decrypt(byte[] fileData, String password) throws FormatException, WrongPasswordException, GeneralSecurityException {
        JSONObject file;
        byte[] salt;
        byte[] nonce;
        byte[] ciphertext;
        int iterations;
        try {
            file = new JSONObject(new String(fileData, StandardCharsets.UTF_8));
            if (!FORMAT.equals(file.optString("format"))) {
                throw new FormatException("not a luckgram backup");
            }
            if (file.optInt("version") != VERSION || !KDF.equals(file.optString("kdf"))) {
                throw new FormatException("unsupported version");
            }
            iterations = file.getInt("iterations");
            salt = Base64.decode(file.getString("salt"));
            nonce = Base64.decode(file.getString("nonce"));
            ciphertext = Base64.decode(file.getString("ciphertext"));
        } catch (JSONException | IllegalArgumentException e) {
            throw new FormatException("damaged file");
        }
        if (iterations < 1 || iterations > MAX_ITERATIONS || salt.length < 8 || nonce.length != NONCE_LENGTH || ciphertext.length < TAG_BITS / 8) {
            throw new FormatException("damaged file");
        }
        byte[] key = deriveKey(password, salt, iterations);
        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            plaintext = cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new WrongPasswordException();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        try {
            return parsePlaintext(plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    // ---------------------------------------------------------------- plaintext

    private static byte[] plaintext(List<Account> accounts) throws JSONException {
        JSONArray array = new JSONArray();
        for (Account account : accounts) {
            JSONObject item = new JSONObject();
            item.put("user_id", Long.toString(account.userId));
            item.put("dc_id", account.dcId);
            item.put("auth_key", Base64.encode(account.authKey));
            item.put("server_salt", Long.toString(account.serverSalt));
            item.put("name", account.name);
            array.put(item);
        }
        JSONObject root = new JSONObject();
        root.put("accounts", array);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<Account> parsePlaintext(byte[] plaintext) throws FormatException {
        try {
            JSONObject root = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            JSONArray array = root.getJSONArray("accounts");
            ArrayList<Account> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                byte[] authKey = Base64.decode(item.getString("auth_key"));
                long userId = Long.parseLong(item.getString("user_id"));
                int dcId = item.getInt("dc_id");
                if (authKey.length != 256 || userId <= 0 || dcId <= 0) {
                    Arrays.fill(authKey, (byte) 0);
                    throw new FormatException("damaged account");
                }
                long serverSalt = Long.parseLong(item.optString("server_salt", "0"));
                result.add(new Account(userId, dcId, authKey, serverSalt, item.optString("name", "")));
            }
            return result;
        } catch (JSONException | IllegalArgumentException e) {
            throw new FormatException("damaged file");
        }
    }

    // ---------------------------------------------------------------- PBKDF2-HMAC-SHA256

    /** PBKDF2-HMAC-SHA256 with a 32-byte output (one block). Written out because
     *  {@code PBKDF2WithHmacSHA256} needs API 26 and the app supports API 23. */
    static byte[] deriveKey(String password, byte[] salt, int iterations) throws GeneralSecurityException {
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(passwordBytes, "HmacSHA256"));
            mac.update(salt);
            mac.update(new byte[]{0, 0, 0, 1});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                mac.update(u);
                mac.doFinal(u, 0);
                for (int j = 0; j < t.length; j++) {
                    t[j] ^= u[j];
                }
            }
            Arrays.fill(u, (byte) 0);
            return t;
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
    }

    // ---------------------------------------------------------------- Base64 (RFC 4648, padded)

    /** Standard Base64 with padding, the same as iOS {@code Data.base64EncodedString()}. */
    static final class Base64 {
        private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        private static final int[] INDEX = new int[128];

        static {
            Arrays.fill(INDEX, -1);
            for (int i = 0; i < ALPHABET.length; i++) {
                INDEX[ALPHABET[i]] = i;
            }
        }

        static String encode(byte[] data) {
            StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
            for (int i = 0; i < data.length; i += 3) {
                int b0 = data[i] & 0xff;
                int b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0;
                int b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
                sb.append(ALPHABET[b0 >> 2]);
                sb.append(ALPHABET[((b0 & 3) << 4) | (b1 >> 4)]);
                sb.append(i + 1 < data.length ? ALPHABET[((b1 & 15) << 2) | (b2 >> 6)] : '=');
                sb.append(i + 2 < data.length ? ALPHABET[b2 & 63] : '=');
            }
            return sb.toString();
        }

        static byte[] decode(String text) {
            String s = text.trim();
            if (s.length() % 4 != 0) {
                throw new IllegalArgumentException("bad base64");
            }
            int padding = s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0;
            byte[] out = new byte[s.length() / 4 * 3 - padding];
            int o = 0;
            for (int i = 0; i < s.length(); i += 4) {
                int[] v = new int[4];
                for (int j = 0; j < 4; j++) {
                    char c = s.charAt(i + j);
                    if (c == '=' && i + j >= s.length() - padding) {
                        v[j] = 0;
                    } else if (c < 128 && INDEX[c] >= 0) {
                        v[j] = INDEX[c];
                    } else {
                        throw new IllegalArgumentException("bad base64");
                    }
                }
                int n = (v[0] << 18) | (v[1] << 12) | (v[2] << 6) | v[3];
                if (o < out.length) out[o++] = (byte) (n >> 16);
                if (o < out.length) out[o++] = (byte) (n >> 8);
                if (o < out.length) out[o++] = (byte) n;
            }
            return out;
        }
    }
}
