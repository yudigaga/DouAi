package com.douai.module.ui;

import android.content.Context;
import android.content.SharedPreferences;
import com.douai.module.config.ConfigTransfer;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import com.douai.module.config.SettingsRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SuppliedHomeIconPackTest {
    private static final String[] PATHS={
            "top/01-recommend","top/02-following","top/03-featured","top/04-trending","top/05-nearby",
            "top/06-live","top/07-shop","top/08-drama","top/09-search","top/10-menu",
            "bottom/01-home","bottom/02-friends","bottom/03-create","bottom/04-messages","bottom/05-profile",
            "feed/01-like","feed/02-comment","feed/03-collect","feed/04-share","feed/05-follow","feed/06-mute","feed/07-sound"};

    private byte[] fixture(String path,boolean selected) throws Exception {
        String resource="/META-INF/douai/supplied-icon-fixtures/"+path+(selected?"-active":"")+".webp";
        try(InputStream stream=getClass().getResourceAsStream(resource)) {
            assertNotNull("Missing independent fixture: "+resource,stream);
            return stream.readAllBytes();
        }
    }

    private BuiltInNavigationIcons.Entry entry(String path) {
        for(BuiltInNavigationIcons.Entry candidate:BuiltInNavigationIcons.ENTRIES)
            if(path.equals(candidate.path))return candidate;
        fail("Missing supplied catalogue entry: "+path);
        return null;
    }

    private byte[] open(BuiltInNavigationIcons.Entry entry,IconState state) throws Exception {
        try(InputStream stream=BuiltInNavigationIcons.open(entry,BuiltInNavigationIcons.styleFor(state))) {
            return stream.readAllBytes();
        }
    }

    @Test public void catalogueContainsExactlyTheTwentyTwoSuppliedDesigns() {
        Set<String> expected=new HashSet<>(java.util.Arrays.asList(PATHS));
        Set<String> actual=new HashSet<>();
        for(BuiltInNavigationIcons.Entry entry:BuiltInNavigationIcons.ENTRIES)
            assertTrue("Duplicate catalogue path: "+entry.path,actual.add(entry.path));
        assertEquals(expected,actual);
    }

    @Test public void allSuppliedFixturesImportWithoutChangingBytes() throws Exception {
        for(String path:PATHS)for(boolean selected:new boolean[]{false,true}) {
            byte[] expected=fixture(path,selected);
            assertArrayEquals("Import changed "+path,expected,HomeIconImport.convert(expected.clone()));
        }
    }

    @Test public void historicalGlassArtworkRemainsAnImportFixtureButBuiltinsOpenAsNewTwoStateSymbols() throws Exception {
        for(String path:PATHS)for(boolean selected:new boolean[]{false,true}) {
            BuiltInNavigationIcons.Entry entry=entry(path);
            IconState day=selected?IconState.DAY_SELECTED:IconState.DAY_NORMAL;
            IconState night=selected?IconState.NIGHT_SELECTED:IconState.NIGHT_NORMAL;
            byte[] oldSupplied=fixture(path,selected);
            assertArrayEquals("User imported bytes remain unchanged: "+path,oldSupplied,HomeIconImport.convert(oldSupplied.clone()));
            byte[] current=open(entry,day);
            assertFalse("Built-in symbol must not keep the glass backplate: "+path,java.util.Arrays.equals(oldSupplied,current));
            assertArrayEquals("Night remains a compatibility alias: "+path,current,open(entry,night));
        }
        for(String path:PATHS)assertFalse("Normal and selected are two distinct states: "+path,
                java.util.Arrays.equals(open(entry(path),IconState.DAY_NORMAL),open(entry(path),IconState.DAY_SELECTED)));
    }

    @Test public void feedDefaultsAreDistinctAndFollowingNavigationRemainsSeparate() {
        String[][] mappings={{"feed_like","feed/01-like"},{"feed_comment","feed/02-comment"},
                {"feed_collect","feed/03-collect"},{"feed_share","feed/04-share"},
                {"feed_follow","feed/05-follow"},{"feed_mute","feed/06-mute"}};
        Set<String> paths=new HashSet<>();
        for(String[] mapping:mappings) {
            BuiltInNavigationIcons.Entry entry=BuiltInNavigationIcons.entryForPosition(mapping[0]);
            assertNotNull("Missing default for "+mapping[0],entry);
            assertEquals(mapping[0],mapping[1],entry.path);
            assertTrue("Repeated feed default: "+entry.path,paths.add(entry.path));
        }
        BuiltInNavigationIcons.Entry following=BuiltInNavigationIcons.entryForPosition("nav_top:homepage_follow");
        assertNotNull(following);
        assertEquals("top/02-following",following.path);
        assertNotEquals(following.path,BuiltInNavigationIcons.entryForPosition("feed_follow").path);
    }

    @Test public void allFortyFourFixturesSurviveSettingsAndBackupRoundTrip() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        SharedPreferences sourcePrefs=context.getSharedPreferences("supplied-pack-source",Context.MODE_PRIVATE);
        SharedPreferences targetPrefs=context.getSharedPreferences("supplied-pack-target",Context.MODE_PRIVATE);
        sourcePrefs.edit().clear().commit();targetPrefs.edit().clear().commit();
        try {
            String json="{}";
            for(int i=0;i<PATHS.length;i++) {
                Map<IconState,byte[]> pair=new EnumMap<>(IconState.class);
                pair.put(IconState.DAY_NORMAL,fixture(PATHS[i],false));
                pair.put(IconState.DAY_SELECTED,fixture(PATHS[i],true));
                json=HomeUiCustomizationSettings.putIcons(json,String.format(java.util.Locale.ROOT,"nav_top:fixture_pack_%02d",i+1),pair);
            }
            assertPairs(json);
            SettingsRepository source=new SettingsRepository(sourcePrefs),target=new SettingsRepository(targetPrefs);
            source.putString(HomeUiCustomizationSettings.ICONS,json);
            String backup=ConfigTransfer.export(source,false);
            assertTrue("Full icon backup must respect the existing 2 MiB transfer limit",
                    backup.getBytes(StandardCharsets.UTF_8).length<=ConfigTransfer.MAX_BYTES);
            ConfigTransfer.apply(target,ConfigTransfer.inspect(target,backup));
            assertPairs(target.getString(HomeUiCustomizationSettings.ICONS,"{}"));
        } catch(IllegalArgumentException capacity) {
            fail("Full 44-image settings and backup round trip must fit existing transfer bounds: "+capacity.getMessage());
        } finally {
            sourcePrefs.edit().clear().commit();targetPrefs.edit().clear().commit();
        }
    }

    private void assertPairs(String json) throws Exception {
        Map<String,Map<IconState,String>> actual=HomeUiCustomizationSettings.readEncodedIcons(json);
        assertEquals(PATHS.length,actual.size());
        for(int i=0;i<PATHS.length;i++) {
            String key=String.format(java.util.Locale.ROOT,"nav_top:fixture_pack_%02d",i+1);
            Map<IconState,String> pair=actual.get(key);
            assertNotNull(key,pair);
            for(boolean selected:new boolean[]{false,true}) {
                IconState state=selected?IconState.DAY_SELECTED:IconState.DAY_NORMAL;
                String encoded=pair.get(state);
                assertNotNull(key+" "+state,encoded);
                assertArrayEquals(key+" "+state,fixture(PATHS[i],selected),Base64.getDecoder().decode(encoded));
            }
        }
    }
}
