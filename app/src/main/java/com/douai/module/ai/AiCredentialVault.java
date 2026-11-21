package com.douai.module.ai;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM credential records bound to the owning registry, entry and exact HTTPS endpoint. */
public final class AiCredentialVault {
    public enum Scope { SKILL_SERVICE, MCP_SERVER }
    public interface KeySource { SecretKey load() throws Exception; }

    private static final String KEY_ALIAS = "com.douai.module.ai-extension.v1";
    private static final int GCM_IV_BYTES = 12;
    private static final Pattern BEARER = Pattern.compile("[A-Za-z0-9._~+/=-]{8,2048}");
    private final KeySource keys;

    public AiCredentialVault(KeySource keys) { this.keys = Objects.requireNonNull(keys); }

    public static AiCredentialVault forDevice() {
        return new AiCredentialVault(AiCredentialVault::keyStoreKey);
    }

    public static boolean validBearer(String secret) {
        return secret != null && BEARER.matcher(secret).matches();
    }

    public String seal(Scope scope, String id, String endpoint, String secret) throws GeneralSecurityException {
        if (!validBearer(secret)) throw unavailable();
        byte[] plaintext = secret.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.load());
            cipher.updateAAD(binding(scope, id, endpoint));
            byte[] iv = cipher.getIV();
            if (iv.length != GCM_IV_BYTES) throw unavailable();
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] packed = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception failed) {
            throw unavailable();
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public String open(Scope scope, String id, String endpoint, String ciphertext) throws GeneralSecurityException {
        byte[] plaintext = null;
        try {
            if (ciphertext.isEmpty()) return "";
            if (ciphertext.length() > 4096) throw unavailable();
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.load(), new GCMParameterSpec(128, packed, 0, GCM_IV_BYTES));
            cipher.updateAAD(binding(scope, id, endpoint));
            plaintext = cipher.doFinal(packed, GCM_IV_BYTES, packed.length - GCM_IV_BYTES);
            String result = new String(plaintext, StandardCharsets.UTF_8);
            if (!validBearer(result)) throw unavailable();
            return result;
        } catch (Exception failed) {
            throw unavailable();
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static byte[] binding(Scope scope, String id, String endpoint) {
        if (id == null || id.isEmpty() || id.indexOf('\n') >= 0) throw new IllegalArgumentException();
        AiExtensionHttp.exactEndpoint(endpoint);
        return (scope.name() + "\n" + id + "\n" + endpoint).getBytes(StandardCharsets.UTF_8);
    }

    private static synchronized SecretKey keyStoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static GeneralSecurityException unavailable() {
        return new GeneralSecurityException("本机凭据不可用");
    }
}
