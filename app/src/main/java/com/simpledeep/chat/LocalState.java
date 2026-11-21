package com.simpledeep.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class LocalState {
    private static final String KEY_ALIAS = "deepseek-api-key-v1";
    static final int MAX_TURNS = 30;
    private final SharedPreferences preferences;

    LocalState(Context context) {
        preferences = context.getSharedPreferences("simpledeep", Context.MODE_PRIVATE);
    }

    String loadKey() throws GeneralSecurityException, IOException {
        String saved = preferences.getString("key", null);
        if (saved == null) return null;
        String[] fields = saved.split("\\.", -1);
        if (fields.length != 2) throw new IOException("Invalid encrypted key");
        SecretKey secret = secretKey(false);
        if (secret == null) throw new GeneralSecurityException("Missing encryption key");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        try {
            cipher.init(Cipher.DECRYPT_MODE, secret,
                    new GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)));
            byte[] clear = cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP));
            return new String(clear, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid encrypted key", e);
        }
    }

    boolean setupPending() {
        // Older installations with a saved key already know how to reach chat.
        return preferences.getBoolean("setupPending", false);
    }

    boolean checkoutPending() {
        return preferences.getBoolean("checkoutPending", false);
    }

    void setCheckoutPending(boolean pending) {
        preferences.edit().putBoolean("checkoutPending", pending).apply();
    }

    void finishSetup() {
        preferences.edit().putBoolean("setupPending", false).apply();
    }

    void saveKey(String key, boolean setupPending) throws GeneralSecurityException, IOException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(true));
        byte[] encrypted = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!preferences.edit().putString("key", value).putBoolean("setupPending", setupPending)
                .remove("checkoutPending").commit()) {
            throw new IOException("Could not save encrypted key");
        }
    }

    void resetBrokenKey() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        store.deleteEntry(KEY_ALIAS);
        if (!preferences.edit().remove("key").commit()) {
            throw new IOException("Could not reset encrypted key");
        }
    }

    private SecretKey secretKey(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey stored = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (stored != null || !create) return stored;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    List<DeepSeekApi.Message> loadChat() {
        List<DeepSeekApi.Message> messages = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(preferences.getString("chat", "[]"));
            for (int i = 0; i + 1 < saved.length(); i += 2) {
                messages.add(new DeepSeekApi.Message("user", saved.getString(i)));
                messages.add(new DeepSeekApi.Message("assistant", saved.getString(i + 1)));
            }
        } catch (JSONException e) {
            messages.clear();
        }
        return messages;
    }

    void saveChat(List<DeepSeekApi.Message> messages, String draft) {
        JSONArray saved = new JSONArray();
        int start = Math.max(0, messages.size() - MAX_TURNS * 2);
        for (int i = start; i < messages.size(); i++) saved.put(messages.get(i).content);
        preferences.edit().putString("chat", saved.toString()).putString("draft", draft).apply();
    }

    String loadDraft() {
        return preferences.getString("draft", "");
    }

    boolean thinking() {
        return preferences.getBoolean("thinking", false);
    }

    void setThinking(boolean enabled) {
        preferences.edit().putBoolean("thinking", enabled).apply();
    }
}
