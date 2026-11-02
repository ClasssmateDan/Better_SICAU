package com.dan.qrbatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.dan.qrbatch.core.Account;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores sessions and a submission journal, never account passwords. */
final class SessionStore {
    static final class Snapshot {
        final List<Account> accounts = new ArrayList<>();
        final Set<String> completed = new LinkedHashSet<>();
        final Set<String> uncertain = new LinkedHashSet<>();
    }
    private static final String ALIAS = "kejian_learningpass_accounts_v1";
    private static final byte[] AAD = "kejian-learningpass-snapshot-v1".getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences preferences;

    SessionStore(Context context) { preferences = context.getSharedPreferences("learningpass_encrypted_sessions", Context.MODE_PRIVATE); }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    synchronized Snapshot load() throws Exception {
        Snapshot snapshot = new Snapshot();
        String encrypted = preferences.getString("snapshot", null);
        if (encrypted == null) return snapshot;
        JSONObject envelope = new JSONObject(encrypted);
        byte[] iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP);
        if (iv.length != 12) throw new IllegalStateException("本地数据格式无效。");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(AAD);
        byte[] plain = cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP));
        JSONObject data = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        JSONArray users = data.optJSONArray("accounts");
        if (users != null) for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.getJSONObject(i);
            JSONObject savedCookies = user.getJSONObject("cookies");
            Map<String, String> cookies = new LinkedHashMap<>();
            Iterator<String> names = savedCookies.keys();
            while (names.hasNext()) { String name = names.next(); cookies.put(name, savedCookies.getString(name)); }
            Account account = new Account(user.getString("phone"), user.getString("uid"), user.getString("name"), user.getString("fid"), cookies);
            account.selected = user.optBoolean("selected", true);
            snapshot.accounts.add(account);
        }
        readKeys(data.optJSONArray("completed"), snapshot.completed);
        readKeys(data.optJSONArray("uncertain"), snapshot.uncertain);
        java.util.Arrays.fill(plain, (byte) 0);
        return snapshot;
    }

    synchronized void save(Snapshot snapshot) throws Exception {
        JSONArray users = new JSONArray();
        for (Account account : snapshot.accounts) {
            JSONObject user = new JSONObject();
            JSONObject cookies;
            synchronized (account.cookies) { cookies = new JSONObject(new LinkedHashMap<>(account.cookies)); }
            user.put("phone", account.phone).put("uid", account.uid).put("name", account.name).put("fid", account.fid)
                    .put("selected", account.selected).put("cookies", cookies);
            users.put(user);
        }
        JSONObject data = new JSONObject().put("accounts", users)
                .put("completed", new JSONArray(new ArrayList<>(snapshot.completed)))
                .put("uncertain", new JSONArray(new ArrayList<>(snapshot.uncertain)));
        byte[] plain = data.toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(AAD);
        byte[] encrypted = cipher.doFinal(plain);
        java.util.Arrays.fill(plain, (byte) 0);
        JSONObject envelope = new JSONObject().put("version", 1)
                .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        if (!preferences.edit().putString("snapshot", envelope.toString()).commit()) {
            throw new IllegalStateException("本地加密保存失败，已停止继续操作。");
        }
    }

    synchronized void clear() throws Exception {
        if (!preferences.edit().clear().commit()) throw new IllegalStateException("清空本地数据失败。");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
    }
    private static void readKeys(JSONArray array, Set<String> target) throws Exception {
        if (array != null) for (int i = 0; i < array.length(); i++) target.add(array.getString(i));
    }
}
