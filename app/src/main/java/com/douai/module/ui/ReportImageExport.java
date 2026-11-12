package com.douai.module.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** One immutable drawing; bounded strip PNG or bounded page images in one portable PDF. */
final class ReportImageExport {
    static final int WIDTH = 1080, PAGE_HEIGHT = 4096, MAX_PAGES = 64;
    static final int MAX_LONG_EDGE = 32760, MAX_LONG_PIXELS = 24_000_000;
    private static final int STRIP_HEIGHT = 128, PNG_FOOTER = 40;
    private static final int PDF_WIDTH = 1440, PDF_HEIGHT = 2038, PDF_HEADER = 76, PDF_FOOTER = 48;
    enum Format {
        LONG_PNG("image/png", "png"), PDF("application/pdf", "pdf");
        final String mimeType, extension;
        Format(String mimeType, String extension) { this.mimeType = mimeType; this.extension = extension; }
    }
    static final class LongPngLimitException extends IllegalArgumentException {
        LongPngLimitException() { super("报告超过单张长图尺寸限制，请选择 PDF 导出完整内容"); }
    }
    private ReportImageExport() { }

    static Document capture(View report, String range, boolean dark, int outputWidth, int pageHeight) {
        return capture(report, range, dark, outputWidth, pageHeight, 360);
    }

    static Document capture(View report, String range, boolean dark, int outputWidth, int pageHeight, int logicalLayoutWidth) {
        if (outputWidth < 240 || outputWidth > WIDTH || pageHeight < 512 || pageHeight > PAGE_HEIGHT
                || logicalLayoutWidth < 240 || logicalLayoutWidth > 1080)
            throw new IllegalArgumentException("报告导出尺寸不受支持");
        int layoutWidth = Math.round(logicalLayoutWidth * report.getResources().getDisplayMetrics().density);
        if (layoutWidth < 1 || layoutWidth > 4096) throw new IllegalArgumentException("报告布局尺寸过大");
        float pngScale = (float) outputWidth / layoutWidth, pdfScale = (float) PDF_WIDTH / layoutWidth;
        // A4 limits actual PDF page content. The caller's pageHeight is an additional content-band budget.
        int pageContent = Math.min((int) Math.floor((PDF_HEIGHT - PDF_HEADER - PDF_FOOTER) / pdfScale),
                (int) Math.floor((pageHeight - PDF_HEADER - PDF_FOOTER) / pngScale));
        int oldWidth = report.getWidth(), oldLeft = report.getLeft(), oldTop = report.getTop();
        try {
            report.measure(View.MeasureSpec.makeMeasureSpec(layoutWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int height = report.getMeasuredHeight();
            if (height <= 0 || height > (long) pageContent * MAX_PAGES)
                throw new IllegalArgumentException("报告内容超过 PDF 页数限制，请缩小统计范围");
            report.layout(0, 0, layoutWidth, height);
            List<int[]> blocks = new ArrayList<>(); collectBlocks(report, 0, pageContent, blocks);
            List<Integer> cuts = new ArrayList<>(); cuts.add(0);
            int start = 0;
            while (start < height) {
                int cut = safeCut(Math.min(height, start + pageContent), blocks);
                if (cut <= start || cuts.size() > MAX_PAGES)
                    throw new IllegalArgumentException("报告无法完整分页，请缩小统计范围");
                cuts.add(cut); start = cut;
            }
            if (cuts.size() > 2 && height - cuts.get(cuts.size() - 2) < pageContent / 4) {
                int beforeLast = cuts.get(cuts.size() - 3);
                int balanced = safeCut(beforeLast + (height - beforeLast) / 2, blocks);
                if (balanced > beforeLast && balanced - beforeLast <= pageContent && height - balanced <= pageContent)
                    cuts.set(cuts.size() - 2, balanced);
            }
            Picture drawing = new Picture(); Canvas recording = drawing.beginRecording(layoutWidth, height);
            try { report.draw(recording); } finally { drawing.endRecording(); }
            return new Document(drawing, outputWidth, height, pngScale, pdfScale, cuts, range, dark);
        } finally {
            if (oldWidth > 0) {
                report.measure(View.MeasureSpec.makeMeasureSpec(oldWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                report.layout(oldLeft, oldTop, oldLeft + oldWidth, oldTop + report.getMeasuredHeight());
            }
            report.requestLayout();
        }
    }

    private static int safeCut(int cut, List<int[]> blocks) {
        int previous;
        do {
            previous = cut;
            for (int[] block : blocks) if (block[0] < cut && block[1] > cut) cut = Math.min(cut, block[0]);
        } while (cut < previous);
        return cut;
    }

    private static void collectBlocks(View view, int top, int maxHeight, List<int[]> blocks) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (("report.keepTogether".equals(view.getTag()) || isHeading(view)) && view.getHeight() <= maxHeight) {
            blocks.add(new int[]{top, top + view.getHeight()});
            int end = isHeading(view) ? headingContentEnd(view, top, maxHeight) : top + view.getHeight();
            if (end > top + view.getHeight() && end - top <= maxHeight) blocks.add(new int[]{top, end});
        }
        if (view instanceof TextView && ((TextView) view).getLayout() != null) {
            TextView text = (TextView) view;
            for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                int from = top + text.getCompoundPaddingTop() + text.getLayout().getLineTop(line);
                int to = top + text.getCompoundPaddingTop() + text.getLayout().getLineBottom(line);
                if (to - from > maxHeight) throw new IllegalArgumentException("报告文字尺寸过大，无法完整分页");
                blocks.add(new int[]{from, to});
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index); collectBlocks(child, top + child.getTop(), maxHeight, blocks);
            }
        } else if (view.getHeight() > 0 && view.getHeight() <= maxHeight) {
            blocks.add(new int[]{top, top + view.getHeight()});
        }
    }

    private static boolean isHeading(View view) {
        return "report.keepWithNext".equals(view.getTag());
    }

    /** Keep a heading with two real text lines, or with its short captions and first fitting chart. */
    private static int headingContentEnd(View heading, int top, int maxHeight) {
        int end = top + heading.getHeight(), fallback = end;
        if (!(heading.getParent() instanceof ViewGroup)) return end;
        ViewGroup parent = (ViewGroup) heading.getParent();
        int parentTop = top - heading.getTop();
        for (int index = parent.indexOfChild(heading) + 1; index < parent.getChildCount(); index++) {
            View next = parent.getChildAt(index);
            if (next.getVisibility() != View.VISIBLE || next.getHeight() <= 0) continue;
            int nextTop = parentTop + next.getTop();
            if (nextTop - top >= maxHeight) break;
            if (isHeading(next)) {
                int nested = headingContentEnd(next, nextTop, maxHeight);
                return nested - top <= maxHeight ? nested : fallback;
            }
            if (next instanceof TextView && ((TextView) next).getLayout() != null) {
                TextView text = (TextView) next;
                if (fallback == end) fallback = firstTextLinesEnd(text, nextTop, 2);
                // Only short captions may be crossed while looking for a chart or subheading.
                if (text.getLayout().getLineCount() > 3) break;
                continue;
            }
            int bottom = nextTop + next.getHeight();
            String tag = next.getTag() instanceof String ? (String) next.getTag() : "";
            if ((tag.startsWith("analytics.portrait.") && tag.endsWith(".chart")) || fallback == end)
                return bottom - top <= maxHeight ? bottom : fallback;
            break;
        }
        return fallback;
    }

    private static int firstTextLinesEnd(TextView text, int top, int lines) {
        int last = text.getLayout().getLineCount() - 1, meaningful = 0;
        CharSequence value = text.getText();
        for (int line = 0; line <= last; line++) {
            int start = text.getLayout().getLineStart(line), end = text.getLayout().getLineEnd(line);
            boolean visible = false;
            for (int at = start; at < end; at++) if (!Character.isWhitespace(value.charAt(at))) { visible = true; break; }
            if (visible && ++meaningful == lines) { last = line; break; }
        }
        return top + text.getCompoundPaddingTop() + text.getLayout().getLineBottom(Math.max(0, last));
    }

    static final class Document {
        private final Picture drawing;
        private final int width, height;
        private final float pngScale, pdfScale;
        private final List<Integer> cuts;
        private final String range;
        private final boolean dark;
        Document(Picture drawing, int width, int height, float pngScale, float pdfScale, List<Integer> cuts, String range, boolean dark) {
            this.drawing = drawing; this.width = width; this.height = height; this.pngScale = pngScale; this.pdfScale = pdfScale;
            this.cuts = cuts; this.range = range == null ? "" : range; this.dark = dark;
        }
        int contentHeight() { return height; }
        int longPngHeight() { return (int) Math.ceil(height * (double) pngScale) + PNG_FOOTER; }
        int pdfPageCount() { return cuts.size() - 1; }
        void requireFormat(Format format) {
            if (format == null) throw new IllegalArgumentException("请选择报告格式");
            if (format == Format.LONG_PNG && (longPngHeight() > MAX_LONG_EDGE || (long) width * longPngHeight() > MAX_LONG_PIXELS))
                throw new LongPngLimitException();
        }
        void write(Format format, OutputStream output, BooleanSupplier valid) throws IOException {
            requireFormat(format); check(valid);
            if (format == Format.LONG_PNG) writeLongPng(output, valid); else writePdf(output, valid);
        }

        private void writeLongPng(OutputStream output, BooleanSupplier valid) throws IOException {
            DataOutputStream data = new DataOutputStream(output);
            data.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            byte[] header = new byte[13];
            putInt(header, 0, width); putInt(header, 4, longPngHeight()); header[8] = 8; header[9] = 2;
            writeChunk(data, "IHDR", header, header.length);
            ChunkStream chunks = new ChunkStream(data);
            Deflater deflater = new Deflater();
            try {
                DeflaterOutputStream compressed = new DeflaterOutputStream(chunks, deflater, 16384);
                int[] pixels = new int[width * STRIP_HEIGHT]; byte[] row = new byte[1 + width * 3];
                for (int top = 0; top < longPngHeight(); top += STRIP_HEIGHT) {
                    check(valid); int rows = Math.min(STRIP_HEIGHT, longPngHeight() - top);
                    Bitmap strip = Bitmap.createBitmap(width, rows, Bitmap.Config.ARGB_8888);
                    try {
                        Canvas canvas = new Canvas(strip); canvas.drawColor(background()); canvas.translate(0, -top);
                        canvas.save(); canvas.clipRect(0, 0, width, longPngHeight() - PNG_FOOTER);
                        canvas.scale(pngScale, pngScale); canvas.drawPicture(drawing); canvas.restore();
                        Paint text = labelPaint(width / 40f); canvas.drawText("DouAi · 完整本地分析报告", 12, longPngHeight() - 12, text);
                        strip.getPixels(pixels, 0, width, 0, 0, width, rows);
                        for (int y = 0; y < rows; y++) {
                            check(valid); row[0] = 0; rgb(pixels, y * width, width, row, 1); compressed.write(row);
                        }
                    } finally { strip.recycle(); }
                }
                compressed.finish(); chunks.finish(); check(valid); writeChunk(data, "IEND", new byte[0], 0);
            } finally { deflater.end(); }
        }

        private void writePdf(OutputStream output, BooleanSupplier valid) throws IOException {
            PdfWriter pdf = new PdfWriter(output, 2 + 4 * pdfPageCount());
            pdf.text("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
            pdf.object(1); pdf.text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
            pdf.object(2); pdf.text("<< /Type /Pages /Count " + pdfPageCount() + " /Kids [");
            for (int page = 0; page < pdfPageCount(); page++) pdf.text((3 + page * 4) + " 0 R ");
            pdf.text("] >>\nendobj\n");
            for (int page = 0; page < pdfPageCount(); page++) {
                check(valid); int object = 3 + page * 4, image = object + 1, content = object + 2, length = object + 3;
                pdf.object(object); pdf.text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Report " + image
                        + " 0 R >> >> /Contents " + content + " 0 R >>\nendobj\n");
                pdf.object(image); pdf.text("<< /Type /XObject /Subtype /Image /Width " + PDF_WIDTH + " /Height " + PDF_HEIGHT
                        + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length " + length + " 0 R >>\nstream\n");
                long begin = pdf.output.count;
                Bitmap bitmap = Bitmap.createBitmap(PDF_WIDTH, PDF_HEIGHT, Bitmap.Config.ARGB_8888);
                Deflater deflater = new Deflater();
                try {
                    drawPdfPage(new Canvas(bitmap), page);
                    DeflaterOutputStream compressed = new DeflaterOutputStream(pdf.output, deflater, 16384);
                    int[] pixels = new int[PDF_WIDTH]; byte[] row = new byte[PDF_WIDTH * 3];
                    for (int y = 0; y < PDF_HEIGHT; y++) {
                        check(valid); bitmap.getPixels(pixels, 0, PDF_WIDTH, 0, y, PDF_WIDTH, 1);
                        rgb(pixels, 0, PDF_WIDTH, row, 0); compressed.write(row);
                    }
                    compressed.finish();
                } finally { deflater.end(); bitmap.recycle(); }
                long bytes = pdf.output.count - begin;
                pdf.text("\nendstream\nendobj\n"); pdf.object(length); pdf.text(bytes + "\nendobj\n");
                String command = "q\n595 0 0 842 0 0 cm\n/Report Do\nQ\n";
                pdf.object(content); pdf.text("<< /Length " + command.length() + " >>\nstream\n" + command + "endstream\nendobj\n");
            }
            check(valid); pdf.finish();
        }

        private void drawPdfPage(Canvas canvas, int page) {
            canvas.drawColor(0xFFFFFFFF); Paint text = labelPaint(30);
            canvas.drawText("DouAi · 本地分析报告", 28, 40, text); text.setTextSize(24);
            canvas.drawText(range, 620, 40, text);
            canvas.save(); canvas.clipRect(0, PDF_HEADER, PDF_WIDTH, PDF_HEIGHT - PDF_FOOTER);
            canvas.translate(0, PDF_HEADER); canvas.scale(pdfScale, pdfScale); canvas.translate(0, -cuts.get(page));
            canvas.clipRect(0, cuts.get(page), PDF_WIDTH / pdfScale, cuts.get(page + 1)); canvas.drawPicture(drawing); canvas.restore();
            canvas.drawText("第 " + (page + 1) + " / " + pdfPageCount() + " 页 · 完整本地分析报告", 28, PDF_HEIGHT - 16, text);
        }
        private int background() { return dark ? 0xFF141414 : 0xFFF6F8F8; }
        private Paint labelPaint(float size) {
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG); text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(size); text.setColor(dark ? 0xFFD4DADB : 0xFF526061); return text;
        }
    }

    private static void rgb(int[] pixels, int start, int width, byte[] row, int at) {
        for (int x = 0; x < width; x++) {
            int color = pixels[start + x]; row[at++] = (byte) (color >>> 16); row[at++] = (byte) (color >>> 8); row[at++] = (byte) color;
        }
    }
    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24); target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8); target[offset + 3] = (byte) value;
    }
    private static void writeChunk(DataOutputStream output, String type, byte[] bytes, int length) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII); CRC32 crc = new CRC32(); crc.update(name); crc.update(bytes, 0, length);
        output.writeInt(length); output.write(name); output.write(bytes, 0, length); output.writeInt((int) crc.getValue());
    }
    private static final class ChunkStream extends OutputStream {
        private final DataOutputStream output; private final byte[] buffer = new byte[32768]; private int count;
        ChunkStream(DataOutputStream output) { this.output = output; }
        @Override public void write(int value) throws IOException { if (count == buffer.length) finish(); buffer[count++] = (byte) value; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) { int copy = Math.min(length, buffer.length - count); System.arraycopy(bytes, offset, buffer, count, copy);
                count += copy; offset += copy; length -= copy; if (count == buffer.length) finish(); }
        }
        void finish() throws IOException { if (count > 0) { writeChunk(output, "IDAT", buffer, count); count = 0; } }
    }
    private static final class CountingOutput extends OutputStream {
        final OutputStream output; long count;
        CountingOutput(OutputStream output) { this.output = output; }
        @Override public void write(int value) throws IOException { output.write(value); count++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { output.write(bytes, offset, length); count += length; }
    }
    private static final class PdfWriter {
        final CountingOutput output; final long[] offsets;
        PdfWriter(OutputStream output, int objects) { this.output = new CountingOutput(output); offsets = new long[objects + 1]; }
        void text(String value) throws IOException { output.write(value.getBytes(StandardCharsets.ISO_8859_1)); }
        void object(int id) throws IOException { offsets[id] = output.count; text(id + " 0 obj\n"); }
        void finish() throws IOException {
            long crossReference = output.count; text("xref\n0 " + offsets.length + "\n0000000000 65535 f \n");
            for (int id = 1; id < offsets.length; id++) {
                if (offsets[id] > 9_999_999_999L) throw new IOException("报告 PDF 文件过大");
                text(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[id]));
            }
            text("trailer\n<< /Size " + offsets.length + " /Root 1 0 R >>\nstartxref\n" + crossReference + "\n%%EOF\n");
        }
    }
    static void check(BooleanSupplier valid) {
        if (!valid.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
