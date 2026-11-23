package com.douai.module.ai.skills;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public final class SkillImageProviderAddressTest {
    private static final String HOST = "https://images.example.test";
    private static final String PREFIX = HOST + "/gateway/openai/v1";
    private static final String COMPLETE = PREFIX + "/images/generations";

    @Test public void baseModeAppendsTheImageOperationToTheExactGatewayPrefix() {
        assertEquals("", SkillImageProviderAddress.resolve(null, false));
        assertEquals("", SkillImageProviderAddress.resolve("   ", true));
        assertEquals(HOST + "/images/generations", SkillImageProviderAddress.resolve(HOST, false));
        assertEquals(COMPLETE, SkillImageProviderAddress.resolve("  " + PREFIX + "/  ", false));
        assertEquals(HOST + "/private/proxy/images/generations",
                SkillImageProviderAddress.resolve(HOST + "/private/proxy/", false));
    }

    @Test public void pastedImageOperationNeverDuplicatesOrDropsItsTrailingSlash() {
        assertEquals(COMPLETE, SkillImageProviderAddress.resolve(COMPLETE, false));
        assertEquals(COMPLETE + "/", SkillImageProviderAddress.resolve(COMPLETE + "/", false));
        assertEquals(COMPLETE + "/", SkillImageProviderAddress.resolve(COMPLETE + "/", true));
    }

    @Test public void displayModeRoundTripsCanonicalAndExistingExactAddresses() {
        assertEquals(new SkillImageProviderAddress.Form("", false), SkillImageProviderAddress.fromSaved(""));
        String origin = "https://Images.Example.Test:443/gateway/v1";
        String canonical = origin + "/images/generations";
        assertEquals(new SkillImageProviderAddress.Form(origin, false), SkillImageProviderAddress.fromSaved(canonical));
        for (String saved : List.of(canonical, canonical + "/", HOST + "/custom-generate/", HOST + "/v1")) {
            SkillImageProviderAddress.Form form = SkillImageProviderAddress.fromSaved(saved);
            assertEquals(saved, SkillImageProviderAddress.resolve(form.address(), form.complete()));
        }
        assertEquals(new SkillImageProviderAddress.Form(canonical + "/", true),
                SkillImageProviderAddress.fromSaved(canonical + "/"));
        assertEquals(new SkillImageProviderAddress.Form(HOST + "/v1", true),
                SkillImageProviderAddress.fromSaved(HOST + "/v1"));
    }

    @Test public void baseModeCanRemoveLiteralChatSuffixWithoutDroppingTheGatewayPrefix() {
        assertEquals(COMPLETE, SkillImageProviderAddress.resolve(PREFIX + "/chat/completions/", false));
        assertEquals(COMPLETE, SkillImageProviderAddress.resolve(PREFIX + "/responses", false));
        assertEquals(HOST + "/chat/completions/next/images/generations",
                SkillImageProviderAddress.resolve(HOST + "/chat/completions/next", false));
    }

    @Test public void recognizableNonImageAddressesNeverBecomeExplicitGenerationTargets() {
        for (String path : List.of("/chat/completions", "/responses/", "/messages", "/models", "/models/example",
                "/%63hat/completions", "/%6dodels", "/CHAT/COMPLETIONS")) {
            String address = PREFIX + path;
            assertTrue(path, SkillImageProviderAddress.isNonImageOperation(address));
            IllegalArgumentException failure = assertThrows(path, IllegalArgumentException.class,
                    () -> SkillImageProviderAddress.resolve(address, true));
            assertFalse(failure.toString().contains(address));
        }
        for (String path : List.of("/models", "/models/example", "/%63hat/completions", "/%72esponses")) {
            assertThrows(path, IllegalArgumentException.class, () -> SkillImageProviderAddress.resolve(PREFIX + path, false));
        }
        assertFalse(SkillImageProviderAddress.isNonImageOperation(COMPLETE));
        assertFalse(SkillImageProviderAddress.isNonImageOperation(HOST + "/private/models/gateway/v1"));
        assertFalse(SkillImageProviderAddress.isNonImageOperation(null));
    }

    @Test public void invalidAuthoritySchemeAndExtraUrlComponentsAreRejected() {
        for (String address : List.of("http://images.example.test/v1", "https://user@images.example.test/v1",
                PREFIX + "?token=fixture", PREFIX + "#metadata", HOST + "/parent/../v1", "example.com/v1")) {
            assertThrows(IllegalArgumentException.class, () -> SkillImageProviderAddress.resolve(address, false));
            assertThrows(IllegalArgumentException.class, () -> SkillImageProviderAddress.resolve(address, true));
        }
    }
}
