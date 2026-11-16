package com.douai.module.feature;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure metadata contracts; no Android view, bitmap, lifecycle or process transport is simulated. */
public class HomeNavigationMetadataTest {
    @Test public void artworkAndHiddenOnlySavedPositionsRemainEditableTogether() {
        HomeNavigationMetadata metadata=new HomeNavigationMetadata();java.util.List<String> saved=new java.util.ArrayList<>();
        for(int i=0;i<32;i++)saved.add("nav_top:art_"+i);
        for(int i=0;i<32;i++)saved.add("nav_top:hidden_"+i);
        Map<String,String> titles=metadata.titles(saved);
        assertEquals(68,titles.size());assertTrue(titles.containsKey("nav_top:art_31"));assertTrue(titles.containsKey("nav_top:hidden_31"));
    }
    @Test public void configuredIdsCannotReplaceTheFourKnownFriendlyBottomNames() {
        HomeNavigationMetadata metadata=new HomeNavigationMetadata();
        metadata.observe("nav_bottom:HOME","HOME",true);
        metadata.observe("nav_bottom:homepage_profile","homepage_profile",true);
        Map<String,String> titles=metadata.titles(List.of("nav_bottom:homepage_home","nav_bottom:FAMILIAR",
                "nav_bottom:homepage_notification","nav_bottom:USER"));
        assertEquals("下导航 · 首页",titles.get("nav_bottom:homepage_home"));
        assertEquals("下导航 · 朋友",titles.get("nav_bottom:homepage_familiar"));
        assertEquals("下导航 · 消息",titles.get("nav_bottom:homepage_notification"));
        assertEquals("下导航 · 我",titles.get("nav_bottom:homepage_profile"));
        assertFalse(titles.containsKey("nav_bottom:HOME"));assertFalse(titles.containsKey("nav_bottom:USER"));
        metadata.observe("nav_bottom:runtime_home","首页",true);
        titles=metadata.titles(List.of());assertEquals("下导航 · 首页",titles.get("nav_bottom:homepage_home"));
        assertTrue(titles.get("nav_bottom:runtime_home").contains("runtime_home"));
    }
    @Test public void onlyRealObservationsAddTopEntriesAndPlaceholderNamesNeverDowngradeThem() {
        HomeNavigationMetadata metadata=new HomeNavigationMetadata();
        assertTrue(metadata.titles(List.of()).keySet().stream().noneMatch(key->key.startsWith("nav_top:")));
        metadata.observe("nav_top:homepage_nearby","城西镇",true);
        metadata.observe("nav_top:homepage_nearby","homepage_nearby",true);
        metadata.observe("nav_top:homepage_nearby","",true);
        metadata.observe("nav_top:homepage_nearby","同城预加载",false);
        assertEquals("上导航 · 城西镇",metadata.titles(List.of()).get("nav_top:homepage_nearby"));
        metadata.observe("nav_top:homepage_nearby","城东镇",true);
        assertEquals("上导航 · 城东镇",metadata.titles(List.of()).get("nav_top:homepage_nearby"));
        assertFalse(metadata.titles(List.of()).containsKey("nav_top:homepage_mall"));
    }
    @Test public void unknownAndDuplicateNamesKeepTheirExactIdsWhileSnapshotsAreImmutable() {
        HomeNavigationMetadata metadata=new HomeNavigationMetadata();
        metadata.observe("nav_top:runtime_a","专题",true);metadata.observe("nav_top:runtime_b","专题",true);
        Map<String,String> first=metadata.titles(List.of("nav_top:saved_only"));
        assertTrue(first.get("nav_top:runtime_a").contains("专题"));assertTrue(first.get("nav_top:runtime_a").contains("runtime_a"));
        assertTrue(first.get("nav_top:runtime_b").contains("runtime_b"));assertNotEquals(first.get("nav_top:runtime_a"),first.get("nav_top:runtime_b"));
        assertTrue(first.get("nav_top:saved_only").contains("未命名"));assertTrue(first.get("nav_top:saved_only").contains("saved_only"));
        assertThrows(UnsupportedOperationException.class,()->first.put("nav_top:fake","fake"));
        metadata.observe("nav_top:runtime_a","新专题",true);
        assertFalse(first.get("nav_top:runtime_a").contains("新专题"));
        assertEquals("上导航 · 新专题",metadata.titles(List.of()).get("nav_top:runtime_a"));
    }
    @Test public void registryIsBoundedAndOnlyRetainsStringMetadata() throws Exception {
        HomeNavigationMetadata metadata=new HomeNavigationMetadata();
        for(int i=0;i<HomeNavigationMetadata.CAPACITY;i++)metadata.observe("nav_top:channel_"+i,"频道"+i,true);
        metadata.observe("nav_top:channel_0","频道0",true);
        metadata.observe("nav_top:channel_new","新频道",true);
        Map<String,String> titles=metadata.titles(List.of());
        assertEquals(HomeNavigationMetadata.CAPACITY+4,titles.size());
        assertTrue(titles.containsKey("nav_top:channel_0"));assertFalse(titles.containsKey("nav_top:channel_1"));
        String longName="😀".repeat(60);metadata.observe("nav_top:long_label",longName,true);
        String displayed=metadata.titles(List.of()).get("nav_top:long_label");
        assertTrue(displayed.codePointCount(0,displayed.length())<60);
        for(var field:HomeNavigationMetadata.class.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers()))
            assertTrue(field.toString(),Map.class.isAssignableFrom(field.getType()));
        for(var type:HomeNavigationMetadata.class.getDeclaredClasses())for(var field:type.getDeclaredFields())
            assertTrue(field.toString(),field.getType()==String.class || field.getType()==int.class);
    }
    @Test public void aFreshProcessModelHasOnlyDefaultsAndSavedKeysWithoutInventingObservedChannels() {
        HomeNavigationMetadata host=new HomeNavigationMetadata();host.observe("nav_top:homepage_follow","关注",true);
        HomeNavigationMetadata standalone=new HomeNavigationMetadata();
        assertFalse(standalone.titles(List.of()).containsKey("nav_top:homepage_follow"));
        assertTrue(standalone.titles(List.of("nav_top:homepage_follow")).get("nav_top:homepage_follow").contains("homepage_follow"));
        assertFalse(standalone.titles(List.of()).containsKey("nav_bottom:homepage_publish"));
    }
}
