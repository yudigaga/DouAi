package com.douai.module.scan;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostResourceProfileTest {
    @Test public void feedItemParamsFieldKeepsBothEarlierHostsOnTheirOriginalField() {
        assertEquals("A2", HostResourceProfile.feedItemParamsField(HostAdaptation.PROFILE_406));
        assertEquals("A2", HostResourceProfile.feedItemParamsField(HostAdaptation.PROFILE_408));
        assertEquals("B2", HostResourceProfile.feedItemParamsField(HostAdaptation.PROFILE_410));
        assertThrows(IllegalArgumentException.class, () -> HostResourceProfile.feedItemParamsField(null));
    }

    @Test public void semanticIdentifiersResolveSeparatelyForBothOriginalResourceTables() {
        assertEquals(0x7f0a462d, HostResourceProfile.id(HostAdaptation.PROFILE_406, 0x7f0a463c));
        assertEquals(0x7f0a463c, HostResourceProfile.id(HostAdaptation.PROFILE_408, 0x7f0a463c));
        assertEquals(0x7f0a463c, HostResourceProfile.canonicalId(HostAdaptation.PROFILE_406, 0x7f0a462d));
        assertEquals(0x7f0a2394, HostResourceProfile.id(HostAdaptation.PROFILE_406, 0x7f0a2396));
        assertEquals(0x7f0a2398, HostResourceProfile.canonicalId(HostAdaptation.PROFILE_406, 0x7f0a2396));
        assertEquals(0x7f0d1954, HostResourceProfile.id(HostAdaptation.PROFILE_406, 0x7f0d194a));
        assertEquals(0x7f08444d, HostResourceProfile.id(HostAdaptation.PROFILE_406, 0x7f0844b1));
        assertEquals(0x7f060094, HostResourceProfile.id(HostAdaptation.PROFILE_406, 0x7f060094));
    }

    @Test public void reflectionAliasesAreBoundToTheSelectedOriginalHost() {
        HostAdaptation.Profile latest = HostApkTestProfile.profiles()[2];
        assertEquals("X.0s7o", HostResourceProfile.className(HostAdaptation.PROFILE_406, "X.0YVw"));
        assertEquals("X.0TzN", HostResourceProfile.className(HostAdaptation.PROFILE_408, "X.0YVw"));
        assertEquals("X.131D", HostResourceProfile.className(HostAdaptation.PROFILE_406, "X.1UOy"));
        assertEquals("X.1Cd0", HostResourceProfile.className(HostAdaptation.PROFILE_408, "X.1UOy"));
        assertEquals("X.1fyy", HostResourceProfile.className(HostAdaptation.PROFILE_408, "X.0vM9"));
        assertEquals("X.133H", HostResourceProfile.className(HostAdaptation.PROFILE_406, "X.1U1O"));
        assertEquals("X.1Cue", HostResourceProfile.className(HostAdaptation.PROFILE_408, "X.1U1O"));
        assertEquals("LJJIIJ", HostResourceProfile.viewFactory(HostAdaptation.PROFILE_406));
        assertEquals("LJJIIJZLJL", HostResourceProfile.viewFactory(HostAdaptation.PROFILE_408));
        assertEquals("LJIIIZ", HostResourceProfile.avatarLoader(HostAdaptation.PROFILE_406));
        assertEquals("LJIIJ", HostResourceProfile.avatarLoader(HostAdaptation.PROFILE_408));
        assertEquals(0x7f0a4629, HostResourceProfile.id(latest, 0x7f0a463c));
        assertEquals(0x7f0a463c, HostResourceProfile.canonicalId(latest, 0x7f0a4629));
        assertEquals(0x7f0a1f3c, HostResourceProfile.id(latest, 0x7f0a1f69));
        assertEquals(0x7f0a1f69, HostResourceProfile.canonicalId(latest, 0x7f0a1f3c));
        assertEquals(0x7f0a2572, HostResourceProfile.id(latest, 0x7f0a2596));
        assertEquals(0x7f0a2596, HostResourceProfile.canonicalId(latest, 0x7f0a2572));
        assertEquals(0x7f0a2371, HostResourceProfile.id(latest, 0x7f0a2396));
        assertEquals(0x7f0a2396, HostResourceProfile.canonicalId(latest, 0x7f0a2371));
        assertEquals(0x7f0a2373, HostResourceProfile.id(latest, 0x7f0a2398));
        assertEquals(0x7f0a2398, HostResourceProfile.canonicalId(latest, 0x7f0a2373));
        assertEquals(0x7f0d1959, HostResourceProfile.id(latest, 0x7f0d194a));
        assertEquals(0x7f0d194a, HostResourceProfile.canonicalId(latest, 0x7f0d1959));
        assertEquals(0x7f0a6444, HostResourceProfile.id(latest, 0x7f0a6444));
        assertEquals("X.0v6A", HostResourceProfile.className(latest, "X.0YVw"));
        assertEquals("X.0umH", HostResourceProfile.className(latest, "X.0uok"));
        assertEquals("X.1c8i", HostResourceProfile.className(latest, "X.1U1O"));
        assertEquals("LJJ", HostResourceProfile.viewFactory(latest));
        assertEquals("LJIIJ", HostResourceProfile.avatarLoader(latest));
        assertEquals("LJIILIIL", HostResourceProfile.mediaSourceGetter(latest));
        assertEquals("LJLILLLLZI", HostResourceProfile.chatMethod(latest, "LJLJJLL"));
        assertEquals("LJJZZIII", HostResourceProfile.chatMethod(latest, "LJLIIL"));
        assertEquals("LJJJ", HostResourceProfile.chatMethod(latest, "LJJJIL"));
        assertEquals("LJJJJI", HostResourceProfile.chatMethod(latest, "LJJJJL"));
        assertEquals("LJJJJL", HostResourceProfile.chatMethod(HostAdaptation.PROFILE_408, "LJJJJL"));
        assertEquals("LJJIIZI", HostResourceProfile.textBindMethod(latest));
        assertEquals("LJJIIZ", HostResourceProfile.textBindMethod(HostAdaptation.PROFILE_408));
        assertEquals("LJJJI", HostResourceProfile.ripsLogicProvider(latest));
        assertEquals("LJJJJJ", HostResourceProfile.ripsLogicProvider(HostAdaptation.PROFILE_408));
        assertEquals("LJIIJ", HostResourceProfile.downloadAwemeGetter(latest));
        assertEquals("LJIIJJI", HostResourceProfile.downloadAwemeGetter(HostAdaptation.PROFILE_408));
        assertEquals("LLLJ", HostResourceProfile.diggViewModelGetter(latest));
        assertEquals("LLLILZ", HostResourceProfile.diggViewModelGetter(HostAdaptation.PROFILE_408));
        assertEquals("LJJIIJ", HostResourceProfile.commentSaveClick(latest));
        assertEquals("LJJI", HostResourceProfile.commentSaveClick(HostAdaptation.PROFILE_408));
        assertThrows(IllegalArgumentException.class, () -> HostResourceProfile.id(null, 0x7f0a463c));
    }
}
