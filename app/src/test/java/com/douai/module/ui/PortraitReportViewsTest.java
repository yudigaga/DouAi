package com.douai.module.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PortraitReportViewsTest {
    @Test @Config(qualifiers="notnight-mdpi")
    public void theSameTwoTopicsKeepTheirHuesAndBothTiedEndpointsAcrossScreenAndPaper() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        var snapshot=com.douai.module.analytics.JournalExample.twoWeeks(
                java.nio.file.Files.createTempDirectory(c.getCacheDir().toPath(),"ink").toFile());
        var data=snapshot.portrait;
        var colors=new JournalTopicColors(data.evidence,data.topicTiles,data.comparison);
        for(String surface : List.of("light","dark","paper")) {
            boolean paper=surface.equals("paper"),dark=surface.equals("dark");
            RuntimeEnvironment.setQualifiers(dark?"night-mdpi":"notnight-mdpi");
            var root=new android.widget.LinearLayout(c);
            var host=new PortraitReportViews.Host() {
                public android.widget.LinearLayout section(String title) {return root;}
                public void analysis(android.widget.LinearLayout section,String key) {}
            };
            if(paper)PortraitReportViews.appendPaper(c,data,host);else new PortraitReportViews(c,data,host).journal();
            android.widget.TextView title=root.findViewWithTag("analytics.story.headline");
            var spans=(android.text.Spanned)title.getText();
            Bitmap tiles=render(root.findViewWithTag("analytics.portrait.journal.topics.chart"),400,175);
            Bitmap ranks=render(root.findViewWithTag("analytics.journal.rank-lines"),400,122);
            try {
                for(int i=0;i<2;i++) {
                    String name=data.topicTiles.get(i).label;
                    int label=spans.toString().indexOf(name), expected=colors.color(name,dark);
                    assertTrue(label>=0);
                    assertEquals(colors.color(name,!paper),spans.getSpans(label,label+1,
                            android.text.style.ForegroundColorSpan.class)[0].getForegroundColor());
                    assertEquals("tile: "+name,expected,tiles.getPixel(100+i*200,12));
                    int wide=0;
                    // Only the two tied markers should span five adjacent pixels on this rank.
                    for(int x=346;x<=375;x++) {
                        boolean inside=true;
                        for(int dx=-2;dx<=2;dx++)inside &= ranks.getPixel(x+dx,20)==expected
                                && ranks.getPixel(x+dx,19)==expected;
                        if(inside)wide++;
                    }
                    assertTrue("missing visible #1 marker for "+name+" on "+surface,wide>0);
                }
                assertEquals("The newcomer must sit beyond the connected line end",
                        colors.color("赛事",dark),ranks.getPixel(368,20));
                assertEquals(colors.color("摄影",dark),ranks.getPixel(356,20));
            } finally {tiles.recycle();ranks.recycle();}
        }
    }

    @Test public void repeatedFramesKeepGeometryAndRenderTheSamePixels() throws Exception {
        View chart = rhythm(new ArrayList<>());
        Bitmap first = render(chart, 600, 220);
        Object originalHits = hits(chart).get(0);
        String originalDescription = chart.getContentDescription().toString();
        Bitmap latest = null;
        for (int frame = 0; frame < 100; frame++) {
            if (latest != null) latest.recycle();
            latest = render(chart, 600, 220);
            assertSame(originalHits, hits(chart).get(0));
        }
        assertTrue(first.sameAs(latest));
        assertEquals(originalDescription, chart.getContentDescription().toString());
        assertEquals(168, hits(chart).size());
        first.recycle(); latest.recycle();
    }

    @Test public void selectionChangesOverlayWithoutRebuildingGeometry() throws Exception {
        List<String> selected = new ArrayList<>();
        View chart = rhythm(selected);
        Bitmap before = render(chart, 600, 220);
        Object originalHits = hits(chart).get(0);
        assertTrue(chart.performClick());
        Bitmap after = render(chart, 600, 220);
        assertEquals(1, selected.size());
        assertTrue(selected.get(0).contains("周一"));
        assertSame(originalHits, hits(chart).get(0));
        assertFalse(before.sameAs(after));
        before.recycle(); after.recycle();
    }

    @Test public void resizeAndThemeChangeRebuildGeometryAndKeepAccessibleSelection() throws Exception {
        List<String> selected = new ArrayList<>();
        View chart = rhythm(selected);
        Bitmap initial = render(chart, 600, 220);
        Object original = hits(chart).get(0);
        Bitmap resized = render(chart, 900, 220);
        assertNotSame(original, hits(chart).get(0));
        Object resizedHit = hits(chart).get(0);
        RuntimeEnvironment.setQualifiers("night");
        Bitmap dark = render(chart, 900, 220);
        assertNotSame(resizedHit, hits(chart).get(0));
        assertFalse(resized.sameAs(dark));
        assertTrue(chart.performClick());
        assertTrue(selected.get(0).contains("周一"));
        initial.recycle(); resized.recycle(); dark.recycle();
    }

    private static View rhythm(List<String> selected) throws Exception {
        Class<?> type = Class.forName("com.douai.module.ui.PortraitReportViews$RhythmChart");
        Constructor<?> constructor = type.getDeclaredConstructor(Context.class, long[][].class, Consumer.class);
        constructor.setAccessible(true);
        long[][] values = new long[7][24];
        values[0][0] = 10;
        return (View) constructor.newInstance(RuntimeEnvironment.getApplication(), values, (Consumer<String>) selected::add);
    }

    private static List<?> hits(View chart) throws Exception {
        Field field = chart.getClass().getSuperclass().getDeclaredField("hits");
        field.setAccessible(true);
        return (List<?>) field.get(chart);
    }

    private static Bitmap render(View chart, int width, int height) {
        chart.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        chart.draw(new Canvas(bitmap));
        return bitmap;
    }
}
