package com.douai.module.ai;

import org.junit.Test;

import java.security.GeneralSecurityException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import static org.junit.Assert.*;

public final class AiCredentialVaultTest {
    @Test public void copiedCiphertextCannotUnlockUnderAnotherEndpointOrRegistry() throws Exception {
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        AiCredentialVault vault = new AiCredentialVault(() -> key);
        String token = "synthetic-transport-secret";
        String endpoint = "https://example.com/tool";
        String ciphertext = vault.seal(AiCredentialVault.Scope.SKILL_SERVICE, "orders", endpoint, token);

        assertFalse(ciphertext.contains(token));
        assertEquals(token, vault.open(AiCredentialVault.Scope.SKILL_SERVICE, "orders", endpoint, ciphertext));
        GeneralSecurityException changedEndpoint = assertThrows(GeneralSecurityException.class, () ->
                vault.open(AiCredentialVault.Scope.SKILL_SERVICE, "orders", endpoint + "-v2", ciphertext));
        assertNull(changedEndpoint.getCause());
        assertThrows(GeneralSecurityException.class, () ->
                vault.open(AiCredentialVault.Scope.MCP_SERVER, "orders", endpoint, ciphertext));
        assertThrows(GeneralSecurityException.class, () ->
                vault.open(AiCredentialVault.Scope.SKILL_SERVICE, "another-id", endpoint, ciphertext));
    }
}
