package local.mintv.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Optional "remember password" support.
 *
 * <p>The password is encrypted with an AES-256-GCM key held by the Android Keystore, so the
 * plaintext never touches {@link SharedPreferences}. The ciphertext blob is versioned
 * (1 byte version + 12 byte IV + ciphertext); if the Keystore key is lost (factory reset,
 * lock screen change on some devices), decryption fails soft and the field is simply empty.</p>
 *
 * <p>Tokens do not live here; see {@link Session}. This class only exists so the login
 * screen's checkbox does something safe instead of storing a plaintext secret.</p>
 */
public final class CredentialStore {

    private static final String TAG = "CredentialStore";
    private static final String PREFS = "credentials";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "maxtv_lite_password";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte VERSION = 1;

    private CredentialStore() {
    }

    public static synchronized void remember(Context context, String username, String password) {
        SharedPreferences prefs = prefs(context);
        prefs.edit().putString(KEY_USERNAME, username == null ? "" : username).apply();
        if (password == null || password.isEmpty()) {
            prefs.edit().remove(KEY_PASSWORD).apply();
            return;
        }
        try {
            prefs.edit().putString(KEY_PASSWORD, encrypt(password)).apply();
        } catch (Exception e) {
            Log.w(TAG, "Unable to encrypt saved password; not remembering it", e);
            prefs.edit().remove(KEY_PASSWORD).apply();
        }
    }

    public static String username(Context context) {
        return prefs(context).getString(KEY_USERNAME, "");
    }

    public static String password(Context context) {
        String blob = prefs(context).getString(KEY_PASSWORD, "");
        if (blob.isEmpty()) {
            return "";
        }
        try {
            return decrypt(blob);
        } catch (Exception e) {
            Log.w(TAG, "Unable to decrypt saved password", e);
            return "";
        }
    }

    public static synchronized void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String encrypt(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) {
            throw new GeneralSecurityException("Unexpected GCM IV length");
        }
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] blob = new byte[1 + iv.length + ciphertext.length];
        blob[0] = VERSION;
        System.arraycopy(iv, 0, blob, 1, iv.length);
        System.arraycopy(ciphertext, 0, blob, 1 + iv.length, ciphertext.length);
        return Base64.encodeToString(blob, Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) throws Exception {
        byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
        if (blob.length < 1 + IV_BYTES + 16 || blob[0] != VERSION) {
            throw new GeneralSecurityException("Unsupported credential blob");
        }
        byte[] iv = Arrays.copyOfRange(blob, 1, 1 + IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(blob, 1 + IV_BYTES, blob.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
