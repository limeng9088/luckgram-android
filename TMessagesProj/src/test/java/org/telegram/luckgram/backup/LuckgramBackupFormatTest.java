package org.telegram.luckgram.backup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Luckgram 10: the backup format. {@code test-vector.luckgram-backup} was made by an independent
 * implementation (Python hashlib + cryptography, fake keys only); the iOS tests open the same file.
 */
public class LuckgramBackupFormatTest {

    private static final String VECTOR_PASSWORD = "luckgram-test-1";

    private static byte[] vector() throws Exception {
        try (InputStream in = LuckgramBackupFormatTest.class.getClassLoader().getResourceAsStream("luckgram/test-vector.luckgram-backup")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static byte[] pattern(boolean reversed) {
        byte[] key = new byte[256];
        for (int i = 0; i < 256; i++) {
            key[i] = (byte) (reversed ? 255 - i : i);
        }
        return key;
    }

    @Test
    public void pbkdf2MatchesTheReferenceKey() throws Exception {
        byte[] salt = new byte[16];
        for (int i = 0; i < 16; i++) {
            salt[i] = (byte) i;
        }
        byte[] key = LuckgramBackupFormat.deriveKey(VECTOR_PASSWORD, salt, 600_000);
        StringBuilder hex = new StringBuilder();
        for (byte b : key) {
            hex.append(String.format("%02x", b & 0xff));
        }
        assertEquals("30e89dcab6c6af94ec1840fd9fdc6f0dac40d93ac26a56a0533012fcb708df3a", hex.toString());
    }

    @Test
    public void opensTheSharedTestVector() throws Exception {
        List<LuckgramBackupFormat.Account> accounts = LuckgramBackupFormat.decrypt(vector(), VECTOR_PASSWORD);
        assertEquals(2, accounts.size());
        assertEquals(1234567890L, accounts.get(0).userId);
        assertEquals(2, accounts.get(0).dcId);
        assertEquals(-1234567890123456789L, accounts.get(0).serverSalt);
        assertEquals("工作", accounts.get(0).name);
        assertArrayEquals(pattern(false), accounts.get(0).authKey);
        assertEquals(987654321L, accounts.get(1).userId);
        assertEquals(4, accounts.get(1).dcId);
        assertEquals(0L, accounts.get(1).serverSalt);
        assertEquals("", accounts.get(1).name);
        assertArrayEquals(pattern(true), accounts.get(1).authKey);
    }

    @Test
    public void wrongPasswordIsReported() throws Exception {
        try {
            LuckgramBackupFormat.decrypt(vector(), "luckgram-test-2");
            fail("expected WrongPasswordException");
        } catch (LuckgramBackupFormat.WrongPasswordException expected) {
        }
    }

    @Test
    public void roundTripWithFewIterations() throws Exception {
        ArrayList<LuckgramBackupFormat.Account> accounts = new ArrayList<>();
        accounts.add(new LuckgramBackupFormat.Account(42L, 5, pattern(false), 7L, "Work"));
        byte[] file = LuckgramBackupFormat.encrypt(accounts, "password-123", new byte[16], new byte[12], 1000);
        List<LuckgramBackupFormat.Account> decoded = LuckgramBackupFormat.decrypt(file, "password-123");
        assertEquals(1, decoded.size());
        assertEquals(42L, decoded.get(0).userId);
        assertEquals(5, decoded.get(0).dcId);
        assertEquals(7L, decoded.get(0).serverSalt);
        assertEquals("Work", decoded.get(0).name);
        assertArrayEquals(pattern(false), decoded.get(0).authKey);
    }

    /** Acceptance 4: no account id, key or name readable in the file. */
    @Test
    public void fileHasNoPlaintext() throws Exception {
        ArrayList<LuckgramBackupFormat.Account> accounts = new ArrayList<>();
        accounts.add(new LuckgramBackupFormat.Account(1234567890L, 2, pattern(false), 99L, "Work"));
        String file = new String(LuckgramBackupFormat.encrypt(accounts, "password-123"), StandardCharsets.UTF_8);
        assertFalse(file.contains("1234567890"));
        assertFalse(file.contains("Work"));
        assertFalse(file.contains(LuckgramBackupFormat.Base64.encode(pattern(false)).substring(0, 16)));
        assertFalse(file.contains("auth_key"));
    }

    @Test
    public void base64MatchesTheStandardAlphabet() {
        assertEquals("", LuckgramBackupFormat.Base64.encode(new byte[0]));
        assertEquals("Zg==", LuckgramBackupFormat.Base64.encode("f".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm8=", LuckgramBackupFormat.Base64.encode("fo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("Zm9v", LuckgramBackupFormat.Base64.encode("foo".getBytes(StandardCharsets.US_ASCII)));
        assertArrayEquals(new byte[]{(byte) 0xfb, (byte) 0xff}, LuckgramBackupFormat.Base64.decode("+/8="));
    }
}
