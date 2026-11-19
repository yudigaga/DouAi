package com.douai.module.config;

import android.graphics.Color;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.EnumMap;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import com.douai.module.feature.MediaMemoryBudget;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeUiCustomizationSettingsTest {
    @Test public void repeatedValidatedMetadataNeedsNoNewBitmapReservationAndRemainsImmutable() {
        byte[] pixels=HomeUiIconFixtures.webp(8,8,Color.CYAN);
        String json=HomeUiCustomizationSettings.putIcon("{}","nav_bottom:homepage_profile",pixels);
        Map<String,Map<IconState,String>> first=HomeUiCustomizationSettings.readEncodedIcons(json);
        try(MediaMemoryBudget.Reservation occupied=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
            assertNotNull(occupied);
            assertSame(first,HomeUiCustomizationSettings.readEncodedIcons(new String(json)));
            assertArrayEquals(pixels,HomeUiCustomizationSettings.readIcon(json,"nav_bottom:USER",IconState.NIGHT_SELECTED));
            assertThrows(UnsupportedOperationException.class,()->first.put("feed_like",Map.of()));
            assertThrows(UnsupportedOperationException.class,()->first.get("nav_bottom:homepage_profile").put(IconState.DAY_NORMAL,null));
        }
    }

    @Test public void theSuccessfulReadCacheIsSingleEntryAndAFailedMissDoesNotPoisonIt() {
        String red=HomeUiCustomizationSettings.putIcon("{}","feed_like",HomeUiIconFixtures.webp(8,8,Color.RED));
        String blue=HomeUiCustomizationSettings.putIcon("{}","feed_like",HomeUiIconFixtures.webp(8,8,Color.BLUE));
        HomeUiCustomizationSettings.readEncodedIcons(red);
        Map<String,Map<IconState,String>> current=HomeUiCustomizationSettings.readEncodedIcons(blue);
        try(MediaMemoryBudget.Reservation occupied=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
            assertNotNull(occupied);
            assertSame(current,HomeUiCustomizationSettings.readEncodedIcons(blue));
            assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.readEncodedIcons(red));
            assertSame(current,HomeUiCustomizationSettings.readEncodedIcons(blue));
        }
    }

    @Test public void aReadCacheHitRequiresTheExactValidatedJsonAndNeverAcceptsAnInvalidEdit() {
        String json=HomeUiCustomizationSettings.putIcon("{}","feed_collect",HomeUiIconFixtures.webp(8,8,Color.YELLOW));
        Map<String,Map<IconState,String>> valid=HomeUiCustomizationSettings.readEncodedIcons(json);
        assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.readEncodedIcons("{\"feed_collect\":\"invalid\"}"));
        assertSame(valid,HomeUiCustomizationSettings.readEncodedIcons(json));
        try(MediaMemoryBudget.Reservation occupied=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
            assertNotNull(occupied);
            assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.readEncodedIcons(json+" "));
            assertSame(valid,HomeUiCustomizationSettings.readEncodedIcons(json));
        }
    }

    @Test public void oversizedWhitespaceCannotBecomeAnUnboundedSuccessfulCacheEntry() {
        Map<String,Map<IconState,String>> valid=HomeUiCustomizationSettings.readEncodedIcons("{}");
        assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.readEncodedIcons(" ".repeat(HomeUiCustomizationSettings.MAX_JSON_LENGTH+1)));
        assertSame(valid,HomeUiCustomizationSettings.readEncodedIcons("{}"));
        assertTrue(HomeUiCustomizationSettings.readEncodedIcons(" \n\t ").isEmpty());
        assertTrue(HomeUiCustomizationSettings.readEncodedIcons(null).isEmpty());
    }

    @Test public void nativeBottomAliasesResolveToTheFourPublicLogicalLocations() throws Exception {
        String[] raw={"HOME","FAMILIAR","NOTIFICATION","USER"};
        String[] ids={"homepage_home","homepage_familiar","homepage_notification","homepage_profile"};
        byte[] icon=HomeUiIconFixtures.webp(8,8,Color.BLUE);
        JSONObject legacy=new JSONObject();
        for(String type:raw)legacy.put("nav_bottom:"+type,Base64.getEncoder().encodeToString(icon));
        Map<String,Map<IconState,String>> resolved=HomeUiCustomizationSettings.readEncodedIcons(legacy.toString());
        assertEquals(4,resolved.size());
        for(int i=0;i<raw.length;i++) {
            String key="nav_bottom:"+ids[i];
            assertEquals(key,HomeUiCustomizationSettings.canonicalNavigationKey("nav_bottom:"+raw[i]));
            assertTrue(resolved.containsKey(key));assertFalse(resolved.containsKey("nav_bottom:"+raw[i]));
            for(IconState state:IconState.values())assertArrayEquals(icon,HomeUiCustomizationSettings.readIcon(legacy.toString(),key,state));
            assertArrayEquals(icon,HomeUiCustomizationSettings.readIcon(legacy.toString(),key));
        }
        for(String key:new String[]{"nav_top:HOME","nav_bottom:HOME_extra","nav_bottom:home","nav_bottom:runtime_channel"})
            assertEquals(key,HomeUiCustomizationSettings.canonicalNavigationKey(key));
    }
    @Test public void explicitCanonicalLocationShadowsTheWholeRawEntryIncludingMissingAndNullStates() throws Exception {
        byte[] raw=HomeUiIconFixtures.webp(8,8,Color.RED),canonical=HomeUiIconFixtures.webp(8,8,Color.GREEN);
        for(boolean canonicalFirst:new boolean[]{false,true}) {
            JSONObject values=new JSONObject(),states=new JSONObject();
            states.put("day_normal",Base64.getEncoder().encodeToString(canonical));states.put("night_selected",JSONObject.NULL);
            if(canonicalFirst)values.put("nav_bottom:homepage_profile",states);
            values.put("nav_bottom:USER",Base64.getEncoder().encodeToString(raw));
            if(!canonicalFirst)values.put("nav_bottom:homepage_profile",states);
            String json=values.toString();assertEquals(1,HomeUiCustomizationSettings.readEncodedIcons(json).size());
            assertArrayEquals(canonical,HomeUiCustomizationSettings.readIcon(json,"nav_bottom:USER",IconState.DAY_NORMAL));
            for(IconState state:new IconState[]{IconState.DAY_SELECTED,IconState.NIGHT_NORMAL,IconState.NIGHT_SELECTED})
                assertNull(HomeUiCustomizationSettings.readIcon(json,"nav_bottom:homepage_profile",state));
            values.put("nav_bottom:homepage_profile",new JSONObject());
            for(IconState state:IconState.values())assertNull(HomeUiCustomizationSettings.readIcon(values.toString(),"nav_bottom:USER",state));
        }
    }
    @Test public void editingOrRestoringALegacyRawLocationPreservesOtherStatesWithoutRevivingItsAlias() throws Exception {
        byte[] normal=HomeUiIconFixtures.webp(8,8,Color.RED),night=HomeUiIconFixtures.webp(8,8,Color.BLUE),edited=HomeUiIconFixtures.webp(8,8,Color.GREEN);
        JSONObject rawStates=new JSONObject().put("default",Base64.getEncoder().encodeToString(normal))
                .put("night_normal",Base64.getEncoder().encodeToString(night));
        String legacy=new JSONObject().put("nav_bottom:HOME",rawStates).toString();
        String next=HomeUiCustomizationSettings.putIcon(legacy,"nav_bottom:homepage_home",IconState.DAY_SELECTED,edited);
        assertFalse(new JSONObject(next).has("nav_bottom:HOME"));
        assertArrayEquals(normal,HomeUiCustomizationSettings.readIcon(next,"nav_bottom:HOME",IconState.DAY_NORMAL));
        assertArrayEquals(edited,HomeUiCustomizationSettings.readIcon(next,"nav_bottom:homepage_home",IconState.DAY_SELECTED));
        assertArrayEquals(night,HomeUiCustomizationSettings.readIcon(next,"nav_bottom:homepage_home",IconState.NIGHT_NORMAL));
        assertArrayEquals(normal,HomeUiCustomizationSettings.readIcon(next,"nav_bottom:homepage_home",IconState.NIGHT_SELECTED));
        String restored=HomeUiCustomizationSettings.removeIcon(legacy,"nav_bottom:HOME",IconState.DAY_SELECTED);
        assertNull(HomeUiCustomizationSettings.readIcon(restored,"nav_bottom:homepage_home",IconState.DAY_SELECTED));
        assertArrayEquals(normal,HomeUiCustomizationSettings.readIcon(restored,"nav_bottom:homepage_home",IconState.DAY_NORMAL));
        JSONObject both=new JSONObject(next).put("nav_bottom:HOME",rawStates);
        assertEquals("{}",HomeUiCustomizationSettings.removeIcon(both.toString(),"nav_bottom:homepage_home"));
        assertEquals("{}",HomeUiCustomizationSettings.removeIcon(both.toString(),"nav_bottom:HOME"));
    }
    @Test public void aliasesCountAsLogicalLocationsButAllStoredAliasBytesRemainValidated() throws Exception {
        byte[] icon=HomeUiIconFixtures.webp(2,2,Color.BLUE);String encoded=Base64.getEncoder().encodeToString(icon);
        JSONObject values=new JSONObject();
        String[] raw={"HOME","FAMILIAR","NOTIFICATION","USER"};
        String[] ids={"homepage_home","homepage_familiar","homepage_notification","homepage_profile"};
        for(int i=0;i<raw.length;i++){values.put("nav_bottom:"+raw[i],encoded);values.put("nav_bottom:"+ids[i],encoded);}
        for(String key:new String[]{"feed_like","feed_like_selected","feed_collect","feed_collect_selected"})values.put(key,encoded);
        for(int i=0;i<26;i++)values.put("nav_top:channel_"+i,encoded);
        assertEquals(38,values.length());assertEquals(32,HomeUiCustomizationSettings.readEncodedIcons(values.toString()).size());
        assertEquals(38,new JSONObject(HomeUiCustomizationSettings.normalize(values.toString())).length());
        String overflow=new JSONObject(values.toString()).put("nav_top:overflow",encoded).toString();
        assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.normalize(overflow));
        String invalid=new JSONObject().put("nav_bottom:HOME","broken").put("nav_bottom:homepage_home",encoded).toString();
        assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.normalize(invalid));
        byte[] padded=HomeUiIconFixtures.chunk(icon,"JUNK",32768-icon.length-8);String large=Base64.getEncoder().encodeToString(padded);
        // Count every persisted payload, including shadowed aliases, before resolving five logical positions.
        assertEquals(32768,padded.length);assertEquals(43692,large.length());
        JSONObject oversized=new JSONObject();
        for(int i=0;i<raw.length;i++) {
            JSONObject rawStates=new JSONObject(),canonicalStates=new JSONObject();
            for(IconState state:IconState.values()){rawStates.put(state.key,large);canonicalStates.put(state.key,large);}
            oversized.put("nav_bottom:"+raw[i],rawStates);oversized.put("nav_bottom:"+ids[i],canonicalStates);
        }
        JSONObject extraStates=new JSONObject();for(IconState state:IconState.values())extraStates.put(state.key,large);
        oversized.put("nav_top:capacity_fixture",extraStates);
        assertEquals(9,oversized.length());assertEquals(1572912,9*4*large.length());
        assertTrue(9*4*large.length()>HomeUiCustomizationSettings.MAX_TOTAL_BASE64);
        assertTrue(oversized.toString().length()<HomeUiCustomizationSettings.MAX_JSON_LENGTH);
        assertTrue(5<HomeUiCustomizationSettings.MAX_ICONS);
        JSONObject withinBound=new JSONObject(oversized.toString());
        withinBound.getJSONObject("nav_top:capacity_fixture").put(IconState.DAY_NORMAL.key,encoded);
        assertEquals(5,HomeUiCustomizationSettings.readEncodedIcons(withinBound.toString()).size());
        IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,
                ()->HomeUiCustomizationSettings.normalize(oversized.toString()));
        assertTrue("Only the total payload bound rejects this otherwise valid fixture",rejected.getMessage().contains("容量"));
    }
    @Test public void fourStatesPreserveLegacyDefaultsAndExplicitNullBlocksEveryLegacyFallback() throws Exception {
        byte[] normal=HomeUiIconFixtures.webp(8,8,Color.BLUE),selected=HomeUiIconFixtures.webp(8,8,Color.GREEN);
        byte[] night=HomeUiIconFixtures.webp(8,8,Color.WHITE);
        String legacy=HomeUiCustomizationSettings.putIcon("{}","feed_like",normal);
        legacy=HomeUiCustomizationSettings.putIcon(legacy,"feed_like_selected",selected);
        String edited=HomeUiCustomizationSettings.putIcon(legacy,"feed_like",IconState.NIGHT_NORMAL,night);
        assertEquals(Base64.getEncoder().encodeToString(normal),new JSONObject(edited).getJSONObject("feed_like").getString("default"));
        assertArrayEquals(normal,HomeUiCustomizationSettings.readIcon(edited,"feed_like",IconState.DAY_NORMAL));
        assertArrayEquals(selected,HomeUiCustomizationSettings.readIcon(edited,"feed_like",IconState.DAY_SELECTED));
        assertArrayEquals(night,HomeUiCustomizationSettings.readIcon(edited,"feed_like",IconState.NIGHT_NORMAL));
        assertArrayEquals(selected,HomeUiCustomizationSettings.readIcon(edited,"feed_like",IconState.NIGHT_SELECTED));
        String restored=HomeUiCustomizationSettings.removeIcon(edited,"feed_like",IconState.NIGHT_SELECTED);
        assertTrue(new JSONObject(restored).getJSONObject("feed_like").isNull("night_selected"));
        assertNull(HomeUiCustomizationSettings.readIcon(restored,"feed_like",IconState.NIGHT_SELECTED));
        assertArrayEquals(selected,HomeUiCustomizationSettings.readIcon(restored,"feed_like",IconState.DAY_SELECTED));
        assertEquals(legacy,HomeUiCustomizationSettings.normalize(legacy));
    }
    @Test public void missingStatesDoNotBorrowOtherThemesAndAliasOnlySelectionsRemainReadable() {
        byte[] day=HomeUiIconFixtures.webp(4,4,Color.RED),selected=HomeUiIconFixtures.webp(4,4,Color.GREEN);
        String json=HomeUiCustomizationSettings.putIcon("{}","nav_bottom:homepage_home",IconState.DAY_NORMAL,day);
        assertNull(HomeUiCustomizationSettings.readIcon(json,"nav_bottom:homepage_home",IconState.DAY_SELECTED));
        assertNull(HomeUiCustomizationSettings.readIcon(json,"nav_bottom:homepage_home",IconState.NIGHT_NORMAL));
        assertNull(HomeUiCustomizationSettings.readIcon(json,"nav_bottom:homepage_home",IconState.NIGHT_SELECTED));
        json=HomeUiCustomizationSettings.putIcon(json,"feed_collect_selected",selected);
        Map<String,Map<IconState,String>> resolved=HomeUiCustomizationSettings.readEncodedIcons(json);
        assertTrue(resolved.containsKey("feed_collect"));assertFalse(resolved.containsKey("feed_collect_selected"));
        assertEquals(4,resolved.get("feed_collect").size());assertNull(resolved.get("feed_collect").get(IconState.DAY_NORMAL));
        assertArrayEquals(selected,HomeUiCustomizationSettings.readIcon(json,"feed_collect",IconState.NIGHT_SELECTED));
        assertThrows(UnsupportedOperationException.class,()->resolved.put("feed_share",Map.of()));
        assertThrows(UnsupportedOperationException.class,()->resolved.get("feed_collect").put(IconState.DAY_NORMAL,"changed"));
        assertEquals(IconState.NIGHT_SELECTED,IconState.of(true,true));
    }
    @Test public void fourStateUpdatesAreAtomicAndCountLogicalLocationsRatherThanImagesOrAliases() throws Exception {
        byte[] icon=HomeUiIconFixtures.webp(2,2,Color.BLUE);Map<IconState,byte[]> four=new EnumMap<>(IconState.class);
        for(IconState state:IconState.values())four.put(state,icon);
        String json=HomeUiCustomizationSettings.putIcon("{}","feed_like",icon);
        json=HomeUiCustomizationSettings.putIcon(json,"feed_like_selected",icon);
        for(int i=0;i<31;i++)json=HomeUiCustomizationSettings.putIcons(json,"nav_top:channel_"+i,four);
        assertEquals(32,HomeUiCustomizationSettings.readEncodedIcons(json).size());
        String full=json;
        assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.putIcons(full,"feed_share",four));
        four.put(IconState.NIGHT_SELECTED,new byte[]{1,2,3});
        assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.putIcons(full,"feed_like",four));
        assertArrayEquals(icon,HomeUiCustomizationSettings.readIcon(full,"feed_like",IconState.DAY_SELECTED));
        String removed=HomeUiCustomizationSettings.removeIcon(full,"feed_like");
        assertFalse(new JSONObject(removed).has("feed_like"));assertFalse(new JSONObject(removed).has("feed_like_selected"));
        String aliasRemoved=HomeUiCustomizationSettings.removeIcon(full,"feed_like_selected");
        assertArrayEquals(icon,HomeUiCustomizationSettings.readIcon(aliasRemoved,"feed_like"));
    }
    @Test public void nestedValuesRetainStrictDuplicateUnknownAndCombinedImageValidation() throws Exception {
        byte[] icon=HomeUiIconFixtures.webp(2,2,Color.BLUE);String encoded=Base64.getEncoder().encodeToString(icon);
        for(String json:new String[]{"{\"feed_like\":{\"unknown\":\""+encoded+"\"}}",
                "{\"feed_like\":{\"default\":null}}","{\"feed_like\":{\"day_normal\":false}}",
                "{\"feed_like\":{\"day_normal\":\""+encoded+"\",\"day_normal\":null}}",
                "{\"feed_like\":{\"night_selected\":\"not_base64\"}}"})
            assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.normalize(json));
        byte[] padded=HomeUiIconFixtures.chunk(icon,"JUNK",32768-icon.length-8);
        byte[] near=HomeUiIconFixtures.chunk(icon,"JUNK",32730-icon.length-8);
        String large=Base64.getEncoder().encodeToString(padded),shorter=Base64.getEncoder().encodeToString(near);
        assertEquals(32768,padded.length);assertEquals(32730,near.length);
        assertEquals(43692,large.length());assertEquals(43640,shorter.length());
        assertEquals(HomeUiCustomizationSettings.MAX_TOTAL_BASE64-4,35*large.length()+shorter.length());
        assertEquals(HomeUiCustomizationSettings.MAX_TOTAL_BASE64+48,36*large.length());
        JSONObject oversized=new JSONObject();
        for(int i=0;i<9;i++) {
            JSONObject states=new JSONObject();for(IconState state:IconState.values())states.put(state.key,large);
            oversized.put("nav_top:channel_"+i,states);
        }
        JSONObject withinBound=new JSONObject(oversized.toString());
        withinBound.getJSONObject("nav_top:channel_8").put(IconState.NIGHT_SELECTED.key,shorter);
        String within=withinBound.toString(),over=oversized.toString();
        assertEquals(9,oversized.length());assertTrue(oversized.length()<HomeUiCustomizationSettings.MAX_ICONS);
        assertTrue(within.length()<HomeUiCustomizationSettings.MAX_JSON_LENGTH);
        assertTrue(over.length()<HomeUiCustomizationSettings.MAX_JSON_LENGTH);
        String accepted=HomeUiCustomizationSettings.normalize(within);
        assertEquals(9,HomeUiCustomizationSettings.readEncodedIcons(accepted).size());
        assertArrayEquals(near,HomeUiCustomizationSettings.readIcon(accepted,"nav_top:channel_8",IconState.NIGHT_SELECTED));
        IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,
                ()->HomeUiCustomizationSettings.normalize(over));
        assertTrue("Only the total payload bound rejects this otherwise valid nested input",rejected.getMessage().contains("容量"));
        IllegalArgumentException parserRejected=assertThrows(IllegalArgumentException.class,
                ()->HomeUiCustomizationSettings.readEncodedIcons(over));
        assertTrue("The parser must enforce the same total payload bound",parserRejected.getMessage().contains("容量"));
    }
    @Test public void preservesOtherIconsAndRoundTripsTheActualWebpBytes() throws Exception {
        byte[] red = HomeUiIconFixtures.webp(8,8, Color.RED);
        byte[] blue = HomeUiIconFixtures.webp(12,12, Color.BLUE);
        String first = HomeUiCustomizationSettings.putIcon("{}", "feed_like", red);
        String both = HomeUiCustomizationSettings.putIcon(first, "nav_top:homepage_hot", blue);
        assertArrayEquals(red, HomeUiCustomizationSettings.readIcon(both,"feed_like"));
        assertArrayEquals(blue, HomeUiCustomizationSettings.readIcon(both,"nav_top:homepage_hot"));
        String remaining = HomeUiCustomizationSettings.removeIcon(both,"feed_like");
        assertNull(HomeUiCustomizationSettings.readIcon(remaining,"feed_like"));
        assertArrayEquals(blue, HomeUiCustomizationSettings.readIcon(remaining,"nav_top:homepage_hot"));
        assertEquals(2,new JSONObject(both).length());
    }
    @Test public void rejectsAnimatedTruncatedForgedAndOversizedImagesWithoutChangingPriorJson() {
        byte[] valid = HomeUiIconFixtures.webp(8,8,Color.RED);
        String previous = HomeUiCustomizationSettings.putIcon("{}","feed_comment",valid);
        for (byte[] invalid : new byte[][] {
                HomeUiIconFixtures.chunk(valid,"ANIM",6),
                HomeUiIconFixtures.chunk(valid,"ANMF",16),
                Arrays.copyOf(valid,valid.length-1),
                new byte[]{'R','I','F','F',12,0,0,0,'W','E','B','P','V','P','8','L',0,0,0,0},
                HomeUiIconFixtures.webp(513,1,Color.BLUE),
                HomeUiIconFixtures.chunk(valid,"JUNK",32768) }) {
            assertThrows(IllegalArgumentException.class,
                    () -> HomeUiCustomizationSettings.putIcon(previous,"feed_share",invalid));
        }
        assertArrayEquals(valid,HomeUiCustomizationSettings.readIcon(previous,"feed_comment"));
        assertNull(HomeUiCustomizationSettings.readIcon(previous,"feed_share"));
    }
    @Test public void rejectsInvalidKeysValuesTrailingDataAndExcessiveIconCounts() throws Exception {
        byte[] icon=HomeUiIconFixtures.webp(2,2,Color.BLUE);
        String encoded=Base64.getEncoder().encodeToString(icon);
        for(String json:new String[]{"[]","{\"feed_share\":false}","{} garbage","{'feed_share':'"+encoded+"'}",
                "{\"feed_share\":\""+encoded+"\",\"feed_share\":\""+encoded+"\"}",
                "{\"feed_share\":\"not_base64\"}","{\"unknown\":\""+encoded+"\"}"}) {
            assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.normalize(json));
        }
        assertThrows(IllegalArgumentException.class,
                ()->HomeUiCustomizationSettings.putIcon("{}","nav_top:",icon));
        String json="{}";
        for(int i=0;i<32;i++)json=HomeUiCustomizationSettings.putIcon(json,"nav_top:channel_"+i,icon);
        String full=json;
        assertThrows(IllegalArgumentException.class,
                ()->HomeUiCustomizationSettings.putIcon(full,"nav_top:overflow",icon));
        assertEquals(32,new JSONObject(full).length());
    }
    @Test public void enforcesTheCombinedBase64BudgetBeforeAcceptingMoreImages() {
        byte[] small=HomeUiIconFixtures.webp(2,2,Color.GREEN);
        byte[] padded=HomeUiIconFixtures.chunk(small,"JUNK",32768-small.length-8);
        byte[] near=HomeUiIconFixtures.chunk(small,"JUNK",32730-small.length-8);
        String large=Base64.getEncoder().encodeToString(padded);
        String shorter=Base64.getEncoder().encodeToString(near);
        assertEquals(32768,padded.length);assertEquals(32730,near.length);
        assertEquals(43692,large.length());assertEquals(43640,shorter.length());
        assertEquals(HomeUiCustomizationSettings.MAX_TOTAL_BASE64-4,35*large.length()+shorter.length());
        assertEquals(HomeUiCustomizationSettings.MAX_TOTAL_BASE64+48,36*large.length());
        assertTrue(9<HomeUiCustomizationSettings.MAX_ICONS);
        Map<IconState,byte[]> four=new EnumMap<>(IconState.class);
        for(IconState state:IconState.values())four.put(state,padded);
        String json="{}";
        for(int i=0;i<8;i++)json=HomeUiCustomizationSettings.putIcons(json,"nav_top:channel_"+i,four);
        four.put(IconState.NIGHT_SELECTED,near);
        String previous=HomeUiCustomizationSettings.putIcons(json,"nav_top:channel_8",four);
        assertTrue(previous.length()<HomeUiCustomizationSettings.MAX_JSON_LENGTH);
        assertEquals(9,HomeUiCustomizationSettings.readEncodedIcons(previous).size());
        assertArrayEquals(near,HomeUiCustomizationSettings.readIcon(previous,"nav_top:channel_8",IconState.NIGHT_SELECTED));
        IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,
                ()->HomeUiCustomizationSettings.putIcon(previous,"nav_top:channel_8",IconState.NIGHT_SELECTED,padded));
        assertTrue("Only the total payload bound rejects this otherwise valid update",rejected.getMessage().contains("容量"));
        assertArrayEquals(near,HomeUiCustomizationSettings.readIcon(previous,"nav_top:channel_8",IconState.NIGHT_SELECTED));
    }
    @Test public void catalogAcceptsValidWebpAndBackupRejectsInvalidIconsWithoutPublication() throws Exception {
        byte[] icon=HomeUiIconFixtures.webp(8,8,Color.BLUE);
        String icons=HomeUiCustomizationSettings.putIcon("{}","feed_comment",icon);
        byte[] legacyDefault=HomeUiIconFixtures.webp(8,8,Color.MAGENTA);
        icons=HomeUiCustomizationSettings.putIcon(icons,"feed_like",legacyDefault);
        Map<IconState,byte[]> variants=new EnumMap<>(IconState.class);
        variants.put(IconState.DAY_NORMAL,HomeUiIconFixtures.webp(8,8,Color.RED));
        variants.put(IconState.DAY_SELECTED,HomeUiIconFixtures.webp(8,8,Color.GREEN));
        variants.put(IconState.NIGHT_NORMAL,HomeUiIconFixtures.webp(8,8,Color.WHITE));
        variants.put(IconState.NIGHT_SELECTED,HomeUiIconFixtures.webp(8,8,Color.YELLOW));
        icons=HomeUiCustomizationSettings.putIcons(icons,"feed_like",variants);
        icons=HomeUiCustomizationSettings.removeIcon(icons,"feed_like",IconState.NIGHT_SELECTED);
        assertEquals("string",ConfigurationCatalog.transferableTypes().get(HomeUiCustomizationSettings.ICONS));
        assertEquals("boolean",ConfigurationCatalog.transferableTypes().get(HomeUiCustomizationSettings.HIDE_LABELS));
        ConfigKeys.validate(HomeUiCustomizationSettings.ICONS,icons);
        ConfigKeys.validate(HomeUiCustomizationSettings.HIDE_LABELS,true);
        assertThrows(IllegalArgumentException.class,()->ConfigKeys.validate(HomeUiCustomizationSettings.ICONS,"[]"));
        assertThrows(IllegalArgumentException.class,()->ConfigKeys.validate(HomeUiCustomizationSettings.HIDE_LABELS,"true"));
        Context context=org.robolectric.RuntimeEnvironment.getApplication();
        android.content.SharedPreferences sourcePrefs=context.getSharedPreferences("home-ui-source",Context.MODE_PRIVATE);
        android.content.SharedPreferences targetPrefs=context.getSharedPreferences("home-ui-target",Context.MODE_PRIVATE);
        sourcePrefs.edit().clear().commit();targetPrefs.edit().clear().commit();
        SettingsRepository source=new SettingsRepository(sourcePrefs),target=new SettingsRepository(targetPrefs);
        source.putString(HomeUiCustomizationSettings.ICONS,icons);
        source.putBoolean(HomeUiCustomizationSettings.HIDE_LABELS,true);
        String backup=ConfigTransfer.export(source,false);
        ConfigTransfer.apply(target,ConfigTransfer.inspect(target,backup));
        String restored=target.getString(HomeUiCustomizationSettings.ICONS,"{}");
        assertArrayEquals(icon,HomeUiCustomizationSettings.readIcon(restored,"feed_comment"));
        for(IconState state:IconState.values()) {
            if(state==IconState.NIGHT_SELECTED)assertNull(HomeUiCustomizationSettings.readIcon(restored,"feed_like",state));
            else assertArrayEquals(variants.get(state),HomeUiCustomizationSettings.readIcon(restored,"feed_like",state));
        }
        JSONObject restoredStates=new JSONObject(restored).getJSONObject("feed_like");
        assertTrue("Backup must retain the explicit native override rather than drop the state",restoredStates.has("night_selected"));
        assertTrue(restoredStates.isNull("night_selected"));
        assertEquals(Base64.getEncoder().encodeToString(legacyDefault),restoredStates.getString("default"));
        assertTrue(target.getBoolean(HomeUiCustomizationSettings.HIDE_LABELS,false));
        Map<String,?> previous=target.snapshot();
        JSONObject invalid=new JSONObject(backup);
        invalid.getJSONObject("values").getJSONObject(HomeUiCustomizationSettings.ICONS).put("value","[]");
        assertThrows(IllegalArgumentException.class,()->ConfigTransfer.inspect(target,invalid.toString()));
        assertEquals(previous,target.snapshot());
    }
}
