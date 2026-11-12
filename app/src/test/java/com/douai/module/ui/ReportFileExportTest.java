package com.douai.module.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ReportFileExportTest {
    private Context context;
    @Before public void setup() { context = RuntimeEnvironment.getApplication(); }

    @Test public void oneLongPngDecodesPastSixThousandPixelsAndContainsTheOriginalTail() throws Exception {
        ReportImageExport.Document document = bands(68);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.write(ReportImageExport.Format.LONG_PNG, output, () -> true);
        byte[] bytes = output.toByteArray();
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull("Export must be one independently decodable PNG", decoded);
        try {
            assertEquals(360, decoded.getWidth());
            assertTrue("The file must contain the full report, not its first page", decoded.getHeight() >= 6800);
            assertEquals(Color.MAGENTA, decoded.getPixel(20, 6790));
        } finally { decoded.recycle(); }
        assertPngChunks(bytes);
        try (FileOutputStream saved = new FileOutputStream(new File(ReportImageExportTest.artifacts(), "complete-report-long.png"))) { saved.write(bytes); }
    }

    @Test public void pdfIsAnActualSingleA4DocumentContainingAllReportPages() throws Exception {
        ReportImageExport.Document document = bands(8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.write(ReportImageExport.Format.PDF, output, () -> true);
        byte[] bytes = output.toByteArray();
        assertEquals("%PDF-", new String(bytes, 0, 5, StandardCharsets.ISO_8859_1));
        String structure = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(document.pdfPageCount() > 1);
        java.util.regex.Matcher pages = java.util.regex.Pattern.compile("/Type\\s*/Page\\b").matcher(structure);
        int count = 0; while (pages.find()) count++;
        assertEquals(document.pdfPageCount(), count);
        assertTrue(structure.contains("/MediaBox [0 0 595 842]"));
        int pointer = structure.lastIndexOf("startxref\n");
        assertTrue(pointer > 0);
        int end = structure.indexOf('\n', pointer + 10);
        int offset = Integer.parseInt(structure.substring(pointer + 10, end));
        assertEquals("xref", structure.substring(offset, offset + 4));
        assertPdfImageStreams(bytes, document.pdfPageCount());
        File file = new File(ReportImageExportTest.artifacts(), "complete-report-a4.pdf");
        try (FileOutputStream saved = new FileOutputStream(file)) { saved.write(bytes); }
    }

    @Test public void longReportStorageIsOneOwnedPngAndOneReadGrantedSend() throws Exception {
        ReportImageExportTest.MediaProvider provider = ReportImageExportTest.provider(context);
        ReportImageShare.Saved saved = ReportImageShare.save(context, bands(68), ReportImageExport.Format.LONG_PNG, null, () -> true);
        assertEquals("One complete long image must create exactly one owned media row", 1, provider.inserts);
        Intent send = saved.chooser().getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("image/png", send.getType());
        assertEquals(1, send.getClipData().getItemCount());
        assertNotEquals(0, send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        saved.handOff();
    }

    @Test public void pdfStorageUsesOneOwnedDownloadAndReadGrantsWithoutAMainThreadTypeQuery() throws Exception {
        ReportImageExportTest.MediaProvider provider = ReportImageExportTest.provider(context);
        ReportImageShare.Saved saved = ReportImageShare.save(context, bands(8), ReportImageExport.Format.PDF, null, () -> true);
        assertEquals(1, provider.inserts); assertEquals(1, provider.updates);
        assertEquals(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, provider.lastCollection);
        assertEquals("Download/DouAi", provider.lastRelativePath); assertTrue(provider.allPagesWrittenBeforePublish);
        provider.failMainTypeQuery = true;
        Intent chooser = saved.chooser(), send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, send.getAction()); assertEquals("application/pdf", send.getType());
        assertEquals(saved.uri, send.getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals(saved.uri, send.getClipData().getItemAt(0).getUri());
        assertNotEquals(0, chooser.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        saved.handOff(); saved.delete(); assertEquals(0, provider.deletes);
    }

    @Test public void streamCancellationIsObservedInsideEitherFormatAndDoesNotPreventRetry() throws Exception {
        ReportImageExport.Document document = bands(8);
        for (ReportImageExport.Format format : ReportImageExport.Format.values()) {
            java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
            ByteArrayOutputStream stopped = new ByteArrayOutputStream();
            assertThrows(java.util.concurrent.CancellationException.class, () -> document.write(format, stopped, () -> checks.incrementAndGet() < 7));
            assertTrue("An in-progress encoding path must have started before cancellation", stopped.size() > 0);
            ByteArrayOutputStream retry = new ByteArrayOutputStream(); document.write(format, retry, () -> true);
            assertTrue(retry.size() > 100);
        }
    }

    @Test public void explicitLogicalLayoutWidthControlsTheNewPaperWrapping() {
        android.widget.TextView report = new android.widget.TextView(context); report.setTextSize(18);
        report.setText("完整中文报告与可读换行。".repeat(120));
        ReportImageExport.Document narrow = ReportImageExport.capture(report, "今天", false, 1080, 4096);
        ReportImageExport.Document paper = ReportImageExport.capture(report, "今天", false, 1080, 4096, 540);
        assertTrue("A wider logical page should wrap this same text into fewer lines", paper.contentHeight() < narrow.contentHeight());
    }

    @Test public void pdfBreaksBeforeAHeadingWhoseChartOrFirstBodyLinesWouldBeOrphaned() throws Exception {
        LinearLayout chartReport = new LinearLayout(context); chartReport.setOrientation(LinearLayout.VERTICAL);
        chartReport.addView(new View(context), new LinearLayout.LayoutParams(-1, 400));
        LinearLayout heading = new LinearLayout(context); heading.setTag("report.keepWithNext");
        chartReport.addView(heading, new LinearLayout.LayoutParams(-1, 50));
        android.widget.TextView caption = new android.widget.TextView(context); caption.setText("Chart caption"); caption.setTextSize(12);
        chartReport.addView(caption, new LinearLayout.LayoutParams(-1, 20));
        View chart = new View(context); chart.setTag("analytics.portrait.fixture.chart");
        chartReport.addView(chart, new LinearLayout.LayoutParams(-1, 150));
        ReportImageExport.Document withChart = ReportImageExport.capture(chartReport, "今天", false, 360, 900, 360);
        assertEquals("The title, caption and fitting chart must start together", Integer.valueOf(400), cuts(withChart).get(1));

        LinearLayout textReport = new LinearLayout(context); textReport.setOrientation(LinearLayout.VERTICAL);
        textReport.addView(new View(context), new LinearLayout.LayoutParams(-1, 420));
        android.widget.TextView subtitle = new android.widget.TextView(context); subtitle.setText("AI subtitle"); subtitle.setTag("report.keepWithNext");
        textReport.addView(subtitle, new LinearLayout.LayoutParams(-1, 30));
        android.widget.TextView body = new android.widget.TextView(context); body.setTextSize(24); body.setIncludeFontPadding(false);
        body.setText(String.join("\n", java.util.Collections.nCopies(30, "Meaningful body line")));
        textReport.addView(body, new LinearLayout.LayoutParams(-1, -2));
        ReportImageExport.Document withText = ReportImageExport.capture(textReport, "今天", false, 360, 900, 360);
        java.util.List<Integer> breaks = cuts(withText);
        assertEquals("The subtitle must include the body's first two lines", Integer.valueOf(420), breaks.get(1));
        assertEquals(Integer.valueOf(withText.contentHeight()), breaks.get(breaks.size() - 1));
        assertTrue("A long paragraph must remain splittable rather than being locked or lost",
                breaks.stream().anyMatch(cut -> cut > body.getTop() && cut < withText.contentHeight()));
    }

    @Test public void aPageCanBreakBetweenTwoConsecutiveFigures() throws Exception {
        LinearLayout report = new LinearLayout(context); report.setOrientation(LinearLayout.VERTICAL);
        report.addView(new View(context), -1, 130);
        for (int i = 0; i < 2; i++) {
            LinearLayout figure = new LinearLayout(context); figure.setOrientation(LinearLayout.VERTICAL);
            figure.setTag("report.keepTogether");
            figure.addView(new android.widget.TextView(context), -1, 20);
            figure.addView(new View(context), -1, 200);
            report.addView(figure, -1, -2);
        }
        ReportImageExport.Document doc = ReportImageExport.capture(report, "test", false, 360, 900, 360);
        assertEquals("A complete figure fits below the preceding paragraph", Integer.valueOf(350), cuts(doc).get(1));
        assertEquals(2, doc.pdfPageCount());
    }

    @Test public void theJournalFixtureHasNeitherAnAlmostEmptyMiddlePageNorAnOrphanedFamiliarityTitle() throws Exception {
        var sample=com.douai.module.analytics.JournalExample.twoWeeks(
                java.nio.file.Files.createTempDirectory(context.getCacheDir().toPath(),"page-breaks").toFile());
        LinearLayout paper=ReportPresentation.build(context,sample,"最近 7 天",0,null,"","",0);
        ReportImageExport.Document doc=ReportImageExport.capture(paper,"最近 7 天",false,1080,4096,ReportPresentation.LOGICAL_WIDTH);
        List<Integer> breaks=cuts(doc);
        int longest=0;
        for(int i=1;i<breaks.size();i++)longest=Math.max(longest,breaks.get(i)-breaks.get(i-1));
        for(int i=2;i+1<breaks.size();i++)assertTrue("Nearly empty interior page: "+i+" "+breaks,
                breaks.get(i)-breaks.get(i-1)>longest/3);
        // Require the chapter label and the first actual sample row below it on the same page.
        int title=textTop(paper,0,"再次遇见，停留有何不同");
        int row=textTop(paper,0,"近 28 天遇见过");
        assertTrue(title>=0 && row>title);
        for(int cut:breaks)assertFalse("Orphaned familiarity title on a sheet boundary",cut>title && cut<=row);
    }
    private static int textTop(View v,int top,String prefix) {
        if(v instanceof android.widget.TextView && ((android.widget.TextView)v).getText().toString().startsWith(prefix))return top;
        if(v instanceof android.view.ViewGroup g)for(int i=0;i<g.getChildCount();i++) {
            View child=g.getChildAt(i);
            int found=textTop(child,top+child.getTop(),prefix);if(found>=0)return found;
        }
        return -1;
    }

    @Test public void legacyPdfDestinationCreatesOnePortableDocumentWithThePdfMimeType() {
        Intent intent = ReportImageShare.destination(ReportImageExport.Format.PDF);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction());
        assertEquals("application/pdf", intent.getType());
        assertEquals("DouAi-report.pdf", intent.getStringExtra(Intent.EXTRA_TITLE));
    }

    private ReportImageExport.Document bands(int count) {
        LinearLayout report = new LinearLayout(context); report.setOrientation(LinearLayout.VERTICAL);
        for (int index = 0; index < count; index++) {
            View band = new View(context); band.setBackgroundColor(index == count - 1 ? Color.MAGENTA : Color.WHITE);
            report.addView(band, new LinearLayout.LayoutParams(-1, 100));
        }
        return ReportImageExport.capture(report, "最近 7 天", false, 360, 900, 360);
    }

    private static void assertPngChunks(byte[] bytes) throws Exception {
        java.io.DataInputStream input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
        input.skipBytes(8); boolean ended = false; int idats = 0;
        while (!ended) {
            int length = input.readInt(); byte[] type = input.readNBytes(4), content = input.readNBytes(length);
            assertEquals(length, content.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(type); crc.update(content);
            assertEquals("Every chunk must have a valid CRC", (int) crc.getValue(), input.readInt());
            String name = new String(type, StandardCharsets.US_ASCII);
            if (name.equals("IDAT")) { idats++; assertTrue("PNG should publish bounded chunks", length <= 65536); }
            ended = name.equals("IEND");
        }
        assertTrue(idats > 0); assertEquals("No data should follow the complete PNG", 0, input.available());
    }

    @SuppressWarnings("unchecked") private static java.util.List<Integer> cuts(ReportImageExport.Document document) throws Exception {
        java.lang.reflect.Field field = document.getClass().getDeclaredField("cuts"); field.setAccessible(true);
        return (java.util.List<Integer>) field.get(document);
    }

    private static void assertPdfImageStreams(byte[] bytes, int expectedPages) throws Exception {
        String structure = new String(bytes, StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher images = java.util.regex.Pattern.compile("/Subtype /Image /Width ([0-9]+) /Height ([0-9]+).*?/Length ([0-9]+) 0 R >>\\nstream\\n").matcher(structure);
        int count = 0, lastMagenta = 0;
        while (images.find()) {
            int width = Integer.parseInt(images.group(1)), height = Integer.parseInt(images.group(2));
            assertEquals(1440, width); assertEquals(2038, height);
            java.util.regex.Matcher length = java.util.regex.Pattern.compile("\\n" + images.group(3) + " 0 obj\\n([0-9]+)\\nendobj").matcher(structure);
            assertTrue(length.find());
            int encoded = Integer.parseInt(length.group(1));
            java.io.InputStream inflated = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(bytes, images.end(), encoded));
            byte[] row = new byte[width * 3]; int magenta = 0;
            for (int y = 0; y < height; y++) {
                int read = 0; while (read < row.length) { int n = inflated.read(row, read, row.length - read); assertTrue(n > 0); read += n; }
                for (int x = 0; x < width; x++) if ((row[x * 3] & 255) == 255 && row[x * 3 + 1] == 0 && (row[x * 3 + 2] & 255) == 255) magenta++;
                if (y == height - 1) for (int channel = width * 3 - 3; channel < width * 3; channel++)
                    assertEquals("Unused PDF paper must remain white", 255, row[channel] & 255);
            }
            assertEquals("Each page image must contain exactly its declared raster data", -1, inflated.read());
            inflated.close(); count++; lastMagenta = magenta;
        }
        assertEquals(expectedPages, count);
        assertTrue("The PDF's last page must include the original report tail", lastMagenta > 1000);
    }
}
