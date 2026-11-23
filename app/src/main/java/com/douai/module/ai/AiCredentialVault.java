package com.douai.module.ai;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM credential records bound to the owning registry, entry and exact HTTPS endpoint. */
public final class AiCredentialVault {
    public enum Scope { SKILL_SERVICE, MCP_SERVER }
    public interface KeySource { SecretKey load() throws Exception; }

    public static final int MAX_IMAGE_BEARER_CHARS = 4000;
    private static final String KEY_ALIAS = "com.douai.module.ai-extension.v1";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int LEGACY_MAX_CIPHERTEXT = 4096;
    public static final int MAX_IMAGE_CIPHERTEXT =
            ((MAX_IMAGE_BEARER_CHARS + GCM_IV_BYTES + GCM_TAG_BITS / 8 + 2) / 3) * 4;
    private static final int LEGACY_MIN_BEARER = 8;
    private static final Pattern BEARER = Pattern.compile("[A-Za-z0-9._~+/=-]{8,2048}");
    private static final Pattern IMAGE_BEARER = Pattern.compile("[A-Za-z0-9._~+/=-]{1," + MAX_IMAGE_BEARER_CHARS + "}");
    private static final Pattern BEARER_ECHO = Pattern.compile("(?i)\\bBearer[\\p{Zs}\\t]+([A-Za-z0-9._~+/=-]+)");
    private final KeySource keys;

    public AiCredentialVault(KeySource keys) { this.keys = Objects.requireNonNull(keys); }

    public static AiCredentialVault forDevice() {
        return new AiCredentialVault(AiCredentialVault::keyStoreKey);
    }

    public static boolean validBearer(String secret) {
        return secret != null && BEARER.matcher(secret).matches();
    }

    public static boolean validImageBearer(String secret) {
        return secret != null && IMAGE_BEARER.matcher(secret).matches();
    }

    public static boolean containsImageCredential(String value, String bearer) {
        if (value == null || bearer == null || bearer.isEmpty()) return false;
        if (bearer.length() >= LEGACY_MIN_BEARER) return value.contains(bearer);
        if (value.trim().equals(bearer)) return true;
        Matcher fields = BEARER_ECHO.matcher(value);
        while (fields.find()) if (bearer.equals(fields.group(1))) return true;
        return false;
    }

    public static boolean imageEndpointContainsCredential(String endpoint, String bearer) {
        if (endpoint == null || endpoint.isEmpty() || bearer == null || bearer.isEmpty()) return false;
        if (bearer.length() >= LEGACY_MIN_BEARER && endpoint.contains(bearer)) return true;
        URI address = URI.create(endpoint);
        String path = address.getPath();
        if (bearer.length() >= LEGACY_MIN_BEARER) return path != null && path.contains(bearer);
        String host = address.getHost();
        return host != null && ("." + host + ".").contains("." + bearer + ".")
                || hasPathSegment(address.getRawPath(), bearer) || hasPathSegment(path, bearer);
    }

    private static boolean hasPathSegment(String path, String bearer) {
        if (path == null) return false;
        String part = "/" + bearer;
        for (int index = path.indexOf(part); index >= 0; index = path.indexOf(part, index + 1)) {
            int end = index + part.length();
            if (end == path.length() || path.charAt(end) == '/') return true;
        }
        return false;
    }

    public String seal(Scope scope, String id, String endpoint, String secret) throws GeneralSecurityException {
        if (!validBearer(secret)) throw unavailable();
        return sealValid(scope, id, endpoint, secret);
    }

    public String sealImage(String id, String endpoint, String secret) throws GeneralSecurityException {
        if (!validImageBearer(secret)) throw unavailable();
        return sealValid(Scope.SKILL_SERVICE, id, endpoint, secret);
    }

    private String sealValid(Scope scope, String id, String endpoint, String secret) throws GeneralSecurityException {
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
        return open(scope, id, endpoint, ciphertext, false);
    }

    public String openImage(String id, String endpoint, String ciphertext) throws GeneralSecurityException {
        return open(Scope.SKILL_SERVICE, id, endpoint, ciphertext, true);
    }

    private String open(Scope scope, String id, String endpoint, String ciphertext, boolean image)
            throws GeneralSecurityException {
        byte[] plaintext = null;
        try {
            if (ciphertext.isEmpty()) return "";
            if (ciphertext.length() > (image ? MAX_IMAGE_CIPHERTEXT : LEGACY_MAX_CIPHERTEXT)) throw unavailable();
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.load(), new GCMParameterSpec(GCM_TAG_BITS, packed, 0, GCM_IV_BYTES));
            cipher.updateAAD(binding(scope, id, endpoint));
            plaintext = cipher.doFinal(packed, GCM_IV_BYTES, packed.length - GCM_IV_BYTES);
            String result = new String(plaintext, StandardCharsets.UTF_8);
            if (!(image ? validImageBearer(result) : validBearer(result))) throw unavailable();
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
