package com.douai.module.feature;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class CommentAiSuggestionRowsTest {
    @Test public void ownedButtonsRestoreEveryHostChildWhenTheRowIsRecycled() throws Exception {
        FrameLayout parent = new FrameLayout(RuntimeEnvironment.getApplication());
        TextView source = new TextView(parent.getContext());
        source.setTextColor(0x80161823);
        parent.addView(source);
        View hidden = new View(parent.getContext());
        hidden.setVisibility(View.GONE);
        parent.addView(hidden);
        AtomicReference<String> clicked = new AtomicReference<>();
        CommentAiSuggestionRows.Row row = CommentAiSuggestionRows.render(parent, source,
                List.of("动作如何练习？", "配乐是什么？", "有哪些相似视频？"), clicked::set);
        assertEquals(View.GONE, source.getVisibility());
        LinearLayout buttons = row.buttons;
        assertEquals(3, buttons.getChildCount());
        assertTrue(buttons.getChildAt(1).performClick());
        assertEquals("配乐是什么？", clicked.get());
        assertEquals("android.widget.Button", buttons.getChildAt(1).getAccessibilityClassName());
        row.close();
        assertEquals(2, parent.getChildCount());
        assertEquals(View.VISIBLE, source.getVisibility());
        assertEquals(View.GONE, hidden.getVisibility());
        clicked.set(null);
        buttons.getChildAt(1).performClick();
        assertNull(clicked.get());
        row.close();
        assertEquals(2, parent.getChildCount());
    }
}
