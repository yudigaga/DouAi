package com.douai.module.feature;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class VoiceCatalogTest {
    @Test public void typicalNestedVoiceArraysInferIdAndName() {
        List<VoiceCatalog.Entry> entries = parse("{\"code\":0,\"data\":{\"voices\":["
                + "{\"voice_id\":1,\"voice_name\":\"First\"},{\"id\":\"2\",\"name\":\"Second\"}]}}");
        assertEquals(2, entries.size());
        assertEquals("1", entries.get(0).id);
        assertEquals("First", entries.get(0).name);
        assertThrows(UnsupportedOperationException.class, entries::clear);
    }

    @Test public void arbitraryListAndNestedIdNameFieldsPreserveLargeNumericIds() {
        List<VoiceCatalog.Entry> entries = VoiceCatalog.parse("{\"response\":{\"sounds\":[{\"binding\":{"
                + "\"ID\":7696151596120622001},\"display\":{\"label\":\"Voice\"}}]}}",
                "response.sounds", "binding.ID", "display.label");
        assertEquals("7696151596120622001", entries.get(0).id);
        assertEquals("Voice", entries.get(0).name);
    }

    @Test public void rootOrNestedMapsUseTheirActualKeysAsVoiceIds() {
        List<VoiceCatalog.Entry> entries = parse("{\"data\":{\"one\":\"Alpha\",\"2\":{\"name\":\"Beta\"}}}");
        assertEquals("one", entries.get(0).id);
        assertEquals("Alpha", entries.get(0).name);
        assertEquals("2", entries.get(1).id);
        assertEquals("Beta", entries.get(1).name);
    }

    @Test public void configuredArrayIndexAndEscapedKeysCanLocateAList() {
        List<VoiceCatalog.Entry> entries = VoiceCatalog.parse("{\"r\":[{\"voice.list\":[{\"ID\":7,\"title\":\"X\"}]}]}",
                "r[0].voice\\.list", "ID", "title");
        assertEquals("7", entries.get(0).id);
        assertEquals("X", entries.get(0).name);
    }

    @Test public void explicitTxtIdsAreKeptAndDuplicatesAreDeduplicated() {
        List<VoiceCatalog.Entry> entries = parse("1: Warm voice\n2, Bright voice\nvoice_3\tLow voice\n1: Duplicate\n");
        assertEquals(3, entries.size());
        assertEquals("Warm voice", entries.get(0).name);
        assertEquals("voice_3", entries.get(2).id);
        assertEquals("Low voice", entries.get(2).name);
    }

    @Test public void actualNumberedVoiceRowsStripThePeriodFromTheId() {
        List<VoiceCatalog.Entry> entries = parse("1. 叶修\n2. 艾玛\n223. 瓦瓦\n");
        assertEquals(3, entries.size());
        assertEquals("1", entries.get(0).id);
        assertEquals("叶修", entries.get(0).name);
        assertEquals("223", entries.get(2).id);
        assertEquals("瓦瓦", entries.get(2).name);
    }

    @Test public void invalidOrAbsentConfiguredFieldsFailWithoutProviderBodyLeakage() {
        for (String body : new String[]{"", "{", "{\"success\":false,\"message\":\"private-token\"}",
                "<html>private-token</html>", "{\"data\":[]}", "{\"code\":403,\"message\":\"private-token\"}"}) {
            Exception failure = assertThrows(IllegalArgumentException.class, () -> parse(body));
            assertFalse(failure.getMessage().contains("private-token"));
            assertNull(failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> VoiceCatalog.parse("{\"data\":[{\"id\":1,\"name\":\"A\"}]}",
                "absent", "", ""));
        assertThrows(IllegalArgumentException.class, () -> VoiceCatalog.parse("[{\"id\":1,\"name\":\"A\"}]",
                "", "absent", ""));
        assertThrows(IllegalArgumentException.class, () -> VoiceCatalog.parse("1: Warm", "data", "id", "name"));
    }

    @Test public void oldEntriesAndSamplesHaveEmptyImmutableLabels() {
        VoiceCatalog.Entry entry = new VoiceCatalog.Entry("original-id", "Original name");
        assertTrue(entry.labels.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> entry.labels.add("标签"));
        for (VoiceCatalog.Entry parsed : parse("[{\"id\":\"one\",\"name\":\"One\"},{\"id\":\"two\",\"name\":\"Two\"}]")) {
            assertTrue(parsed.labels.isEmpty());
        }
    }

    @Test public void optionalLabelsAreBoundedDeduplicatedSafeAndDetachedFromTheirSource() {
        List<String> original = new ArrayList<>(Arrays.asList(" 中文 ", "中文", null, "", "\n隐藏", "中\u200b文",
                "中\u202e文", "中文\u2028", "中文\ud800", "x".repeat(49)));
        for (int i = 0; i < 12; i++) original.add("标签" + i);
        VoiceCatalog.Entry entry = new VoiceCatalog.Entry("original-id", "Original name", original);
        original.clear();
        assertEquals(Arrays.asList("中文", "标签0", "标签1", "标签2", "标签3", "标签4", "标签5", "标签6"), entry.labels);
        assertEquals("original-id", entry.id); assertEquals("Original name", entry.name);
        assertThrows(UnsupportedOperationException.class, entry.labels::clear);
        assertTrue(new VoiceCatalog.Entry("id", "Name", null).labels.isEmpty());
    }

    @Test public void cachedLabelArraysKeepValidStringsWithoutRejectingAnOtherwiseValidCatalog() {
        List<VoiceCatalog.Entry> entries = parse("[{\"id\":\"one\",\"name\":\"Original\",\"labels\":["
                + "\"语言：中文\",null,{\"bad\":[1,2]},[\"nested\"],\"性别：女声\",\"隐藏\\n文字\"]},"
                + "{\"id\":\"two\",\"name\":\"Second\",\"labels\":{\"unexpected\":true}},"
                + "{\"id\":\"three\",\"name\":\"Third\",\"labels\":null}]");
        assertEquals(3, entries.size());
        assertEquals(Arrays.asList("语言：中文", "性别：女声"), entries.get(0).labels);
        assertTrue(entries.get(1).labels.isEmpty()); assertTrue(entries.get(2).labels.isEmpty());
        assertEquals("Original", entries.get(0).name);
    }

    @Test public void metadataStillObeysTheGlobalCatalogDepthAndNodeBudgets() {
        String deeplyNested = "[".repeat(50) + "null" + "]".repeat(50);
        String tooDeep = "[{\"id\":\"one\",\"name\":\"Original\",\"labels\":[" + deeplyNested + "]}]";
        String tooMany = "[{\"id\":\"one\",\"name\":\"Original\",\"labels\":[" + "null,".repeat(17000) + "null]}]";
        for (String body : new String[]{tooDeep, tooMany}) {
            Exception failure = assertThrows(IllegalArgumentException.class, () -> parse(body));
            assertNull(failure.getCause());
        }
    }

    @Test public void aCustomListPathNamedLabelsStillAcceptsVoiceObjectsAndIdMaps() {
        List<VoiceCatalog.Entry> array = VoiceCatalog.parse("{\"labels\":[{\"id\":\"one\",\"name\":\"First\"},"
                + "{\"id\":\"two\",\"name\":\"Second\"}]}", "labels", "", "");
        assertEquals(2, array.size());
        assertEquals("one", array.get(0).id); assertEquals("First", array.get(0).name);
        assertEquals("two", array.get(1).id); assertEquals("Second", array.get(1).name);
        List<VoiceCatalog.Entry> mapping = VoiceCatalog.parse("{\"labels\":{\"one\":\"First\",\"two\":{\"name\":\"Second\"}}}",
                "labels", "", "");
        assertEquals(2, mapping.size());
        assertEquals("one", mapping.get(0).id); assertEquals("First", mapping.get(0).name);
        assertEquals("two", mapping.get(1).id); assertEquals("Second", mapping.get(1).name);
    }

    @Test public void aCustomNamePathCanReadAnOrdinaryLabelsArrayWithoutLosingItsOriginalString() {
        List<VoiceCatalog.Entry> entries = VoiceCatalog.parse("[{\"id\":\"one\",\"labels\":[\"Original\\nName\",\"Other\"]}]",
                "", "id", "labels[0]");
        assertEquals("one", entries.get(0).id);
        assertEquals("Original\nName", entries.get(0).name);
        assertEquals(Arrays.asList("Other"), entries.get(0).labels);
    }

    @Test public void twoHundredWorstCaseCachedEntriesRemainWithinTheTwoMiBParserBudget() throws Exception {
        JSONArray cached = new JSONArray();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 8; i++) labels.add(i + "\"".repeat(47));
        String name = "A" + "\u0001".repeat(510) + "Z";
        for (int i = 0; i < 200; i++) {
            String index = Integer.toString(i);
            String id = index + "\u0001".repeat(255 - index.length()) + "Z";
            cached.put(VoiceCatalogLabels.cached(new VoiceCatalog.Entry(id, name, labels)));
        }
        String sample = cached.toString();
        assertTrue(sample.getBytes(StandardCharsets.UTF_8).length < 2 * 1024 * 1024);
        List<VoiceCatalog.Entry> roundTrip = parse(sample);
        assertEquals(200, roundTrip.size());
        assertEquals(name, roundTrip.get(199).name);
        assertEquals(labels, roundTrip.get(199).labels);
    }

    private static List<VoiceCatalog.Entry> parse(String body) { return VoiceCatalog.parse(body, "", "", ""); }
}
