package ir.codelighthouse.bazikhooneh.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small Keystore-backed credential store for API and game reconnect tokens. Existing plaintext
 * values are transparently encrypted on first read.
 */
public final class SecurePreferences {
  private static final String KEY_ALIAS = "bazikhooneh_credentials_v1";
  private static final String PREFIX = "enc:v1:";
  private static final int TAG_BITS = 128;
  private final SharedPreferences preferences;

  private SecurePreferences(Context context, String name) {
    preferences = context.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
  }

  public static SecurePreferences open(Context context, String name) {
    return new SecurePreferences(context, name);
  }

  public String getString(String key, String defaultValue) {
    Object stored = preferences.getAll().get(key);
    if (!(stored instanceof String)) return defaultValue;
    String value = (String) stored;
    if (!value.startsWith(PREFIX)) {
      putString(key, value);
      return value;
    }
    try {
      return decrypt(value.substring(PREFIX.length()));
    } catch (Exception error) {
      preferences.edit().remove(key).apply();
      return defaultValue;
    }
  }

  public int getInt(String key, int defaultValue) {
    Object stored = preferences.getAll().get(key);
    if (stored instanceof Number) {
      int value = ((Number) stored).intValue();
      putInt(key, value);
      return value;
    }
    try {
      return Integer.parseInt(getString(key, String.valueOf(defaultValue)));
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  public void putString(String key, String value) {
    try {
      preferences.edit().putString(key, PREFIX + encrypt(value == null ? "" : value)).apply();
    } catch (Exception error) {
      throw new IllegalStateException("Unable to protect local credentials", error);
    }
  }

  public void putInt(String key, int value) {
    putString(key, String.valueOf(value));
  }

  public void clear() {
    preferences.edit().clear().apply();
  }

  private static String encrypt(String plaintext) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    byte[] iv = cipher.getIV();
    ByteBuffer payload = ByteBuffer.allocate(1 + iv.length + ciphertext.length);
    payload.put((byte) iv.length).put(iv).put(ciphertext);
    return Base64.encodeToString(payload.array(), Base64.NO_WRAP);
  }

  private static String decrypt(String encoded) throws Exception {
    ByteBuffer payload = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
    int ivLength = payload.get() & 0xff;
    if (ivLength < 12 || ivLength > 16 || payload.remaining() <= ivLength) {
      throw new IllegalArgumentException("Invalid encrypted credential");
    }
    byte[] iv = new byte[ivLength];
    byte[] ciphertext = new byte[payload.remaining() - ivLength];
    payload.get(iv).get(ciphertext);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
    return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
  }

  private static SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(KEY_ALIAS)) {
      return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
    }
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
    return generator.generateKey();
  }
}
