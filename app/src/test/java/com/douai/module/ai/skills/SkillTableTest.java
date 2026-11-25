package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.douai.module.ai.skills.SkillTable.parse;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SkillTableTest {
    @Test public void parseKeepsCellOrderUnicodeNewlinesAndImmutableRows() throws Exception {
        SkillTable parsed = parse(wire(List.of("城市", "内容"), List.of(List.of("杭州", "第一段\n第二段"),
                List.of("上海", "包含\"引号\""))));
        assertEquals(List.of("城市", "内容"), parsed.columns());
        assertEquals(List.of(List.of("杭州", "第一段\n第二段"), List.of("上海", "包含\"引号\"")), parsed.rows());
        assertEquals(parsed, parse(parsed.toJson()));
        assertThrows(UnsupportedOperationException.class, () -> parsed.columns().add("外部变更"));
        assertThrows(UnsupportedOperationException.class, () -> parsed.rows().get(0).add("外部变更"));
        assertTrue(parse(wire(List.of("列"), List.of())).rows().isEmpty());
    }

    @Test public void malformedDuplicateAndRaggedDataAreRejected() {
        for (String input : List.of("[]", "{\"columns\":[],\"rows\":[]}", "{\"columns\":[\"a\"]}",
                "{\"columns\":[\" \"],\"rows\":[]}", "{\"columns\":[\"a\",\"a\"],\"rows\":[]}",
                "{\"columns\":[\"a\"],\"rows\":[],\"other\":[]}",
                "{\"columns\":[\"a\"],\"columns\":[\"b\"],\"rows\":[]}",
                "{\"columns\":[\"a\"],\"rows\":[],\"rows\":[]}",
                "{\"columns\":[\"a\",\"b\"],\"rows\":[[\"x\"]]}",
                "{\"columns\":[\"a\"],\"rows\":[[\"x\",\"y\"]]}",
                "{\"columns\":[\"a\"],\"rows\":[[12]]}",
                "{\"columns\":[\"a\"],\"rows\":[[null]]}",
                "{\"columns\":[\"a\"],\"rows\":[\"x\"]}",
                "{\"columns\":[\"a\"],\"rows\":[[\"\\q\"]]}",
                "{\"columns\":[\"a\"],\"rows\":[]} {}")) {
            assertThrows(input, IllegalArgumentException.class, () -> parse(input));
        }
    }

    @Test public void byteAndShapeLimitsStillAcceptAFullValidTable() throws Exception {
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < 12; i++) headers.add("列" + i);
        List<String> row = Collections.nCopies(12, "sample-value");
        List<List<String>> full = Collections.nCopies(100, row);
        assertEquals(100, parse(wire(headers, full)).rows().size());

        assertThrows(IllegalArgumentException.class,
                () -> parse(wire(Collections.nCopies(13, "列"), List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> parse(wire(List.of("列"), Collections.nCopies(101, List.of("值")))));
        assertThrows(IllegalArgumentException.class,
                () -> parse(wire(List.of("列"), List.of(List.of("x".repeat(513))))));
        String multibyte = wire(List.of("列"), Collections.nCopies(33, List.of("界".repeat(512))));
        assertTrue(multibyte.length() < 48 * 1024);
        assertTrue(multibyte.getBytes(StandardCharsets.UTF_8).length > 48 * 1024);
        assertThrows(IllegalArgumentException.class, () -> parse(multibyte));
    }

    @Test public void credentialsAreRejectedInDecodedHeadersAndCells() {
        String escaped = "API Key: \\u0066ixture-private-key";
        for (String json : List.of("{\"columns\":[\"" + escaped + "\"],\"rows\":[]}",
                "{\"columns\":[\"列\"],\"rows\":[[\"" + escaped + "\"]]}")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse(json));
            assertFalse(failure.toString().contains("fixture-private-key"));
            assertNull(failure.getCause());
        }
    }

    @Test public void csvQuotesCellsAndDeactivatesFormulaPrefixesWithoutClosingTheDestination() throws Exception {
        List<String> formulas = List.of("=1+1", "  +1", "\t-1", "\r\n@value", "\uFEFF＝1",
                "\u2003\u000B＋2", "\u200E-3", "正常文本");
        SkillTable table = parse(wire(List.of("@列", "文本"), List.of(
                List.of(formulas.get(0), "他说\"好\"\n第二段"),
                List.of(formulas.get(1), formulas.get(2)),
                List.of(formulas.get(3), formulas.get(4)),
                List.of(formulas.get(5), formulas.get(6)),
                List.of(formulas.get(7), ""))));
        CapturingDestination destination = new CapturingDestination();
        table.writeCsv(destination);
        assertFalse(destination.closed);
        String csv = new String(destination.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("\"'@列\",\"文本\"\r\n"
                + "\"'=1+1\",\"他说\"\"好\"\"\n第二段\"\r\n"
                + "\"'  +1\",\"'\t-1\"\r\n"
                + "\"'\r\n@value\",\"'\uFEFF＝1\"\r\n"
                + "\"'\u2003\u000B＋2\",\"'\u200E-3\"\r\n"
                + "\"正常文本\",\"\"\r\n", csv);
    }

    private static String wire(List<String> headers, List<List<String>> rows) throws Exception {
        JSONArray data = new JSONArray();
        for (List<String> row : rows) data.put(new JSONArray(row));
        return new JSONObject().put("columns", new JSONArray(headers)).put("rows", data).toString();
    }

    private static final class CapturingDestination extends ByteArrayOutputStream {
        boolean closed;
        @Override public void close() { closed = true; }
    }
}
