package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Immutable, bounded table data for a Skill result and its local export. */
public record SkillTable(List<String> columns, List<List<String>> rows) {
    private static final int MAX_COLUMNS = 12;
    private static final int MAX_ROWS = 100;
    private static final int MAX_CELL_CHARS = 512;
    private static final int MAX_WIRE_BYTES = 48 * 1024;
    private static final int MAX_CSV_BYTES = 64 * 1024;
    private static final int MAX_JSON_NODES = 3 + MAX_COLUMNS + MAX_ROWS * (1 + MAX_COLUMNS);

    public SkillTable {
        columns = checkedCells(columns, MAX_COLUMNS);
        if (columns.isEmpty() || new HashSet<>(columns).size() != columns.size()
                || columns.stream().anyMatch(column -> column.trim().isEmpty())
                || rows == null || rows.size() > MAX_ROWS) throw invalid();
        List<List<String>> copied = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> cells = checkedCells(row, columns.size());
            if (cells.size() != columns.size()) throw invalid();
            copied.add(cells);
        }
        rows = Collections.unmodifiableList(copied);
        if (!withinWire(json(columns, rows))) throw invalid();
    }

    public static SkillTable parse(String json) {
        if (!withinWire(json)) throw invalid();
        JSONObject table = SkillAuthoring.readObject(json, MAX_WIRE_BYTES, MAX_JSON_NODES);
        if (table.length() != 2) throw invalid();
        List<String> columns = readCells(table.optJSONArray("columns"), MAX_COLUMNS);
        JSONArray data = table.optJSONArray("rows");
        if (data == null || data.length() > MAX_ROWS) throw invalid();
        List<List<String>> rows = new ArrayList<>(data.length());
        for (int index = 0; index < data.length(); index++) rows.add(readCells(data.optJSONArray(index), columns.size()));
        return new SkillTable(columns, rows);
    }

    public String toJson() { return json(columns, rows); }

    /** Writes UTF-8 CSV and leaves the destination open for its owner. */
    public void writeCsv(OutputStream destination) throws IOException {
        StringBuilder csv = new StringBuilder();
        appendCsvRow(csv, columns);
        for (List<String> row : rows) appendCsvRow(csv, row);
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CSV_BYTES) throw new IOException("表格超出导出大小限制");
        destination.write(bytes);
    }

    private static List<String> readCells(JSONArray array, int limit) {
        if (array == null || array.length() > limit) throw invalid();
        List<String> strings = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            Object cell = array.opt(index);
            if (!(cell instanceof String)) throw invalid();
            strings.add((String) cell);
        }
        return strings;
    }

    private static List<String> checkedCells(List<String> cells, int limit) {
        if (cells == null || cells.size() > limit) throw invalid();
        List<String> checked = new ArrayList<>(cells.size());
        for (String text : cells) {
            if (text == null || text.length() > MAX_CELL_CHARS
                    || text.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.SURROGATE)) {
                throw invalid();
            }
            SkillAuthoring.rejectCredential(text);
            checked.add(text);
        }
        return Collections.unmodifiableList(checked);
    }

    private static boolean withinWire(String json) {
        return json != null && json.length() <= MAX_WIRE_BYTES
                && json.getBytes(StandardCharsets.UTF_8).length <= MAX_WIRE_BYTES;
    }

    private static String json(List<String> columns, List<List<String>> rows) {
        JSONArray data = new JSONArray();
        for (List<String> row : rows) data.put(new JSONArray(row));
        try { return new JSONObject().put("columns", new JSONArray(columns)).put("rows", data).toString(); }
        catch (JSONException malformed) { throw invalid(); }
    }

    private static void appendCsvRow(StringBuilder csv, List<String> cells) {
        for (int index = 0; index < cells.size(); index++) {
            if (index != 0) csv.append(',');
            String cell = cells.get(index);
            csv.append('"');
            if (formulaPrefix(cell)) csv.append('\'');
            csv.append(cell.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    private static boolean formulaPrefix(String cell) {
        for (int offset = 0; offset < cell.length();) {
            int codePoint = cell.codePointAt(offset);
            switch (codePoint) {
                case '=': case '+': case '-': case '@':
                case '＝': case '＋': case '－': case '＠':
                case '\t': case '\r': case '\n':
                    return true;
                default:
                    if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                            && !Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT) {
                        return false;
                    }
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("技能表格的结构或大小不符合要求");
    }
}
