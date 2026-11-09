package com.douai.module.ui;

import com.douai.module.config.AutomationHistoryStore.Entry;
import com.douai.module.config.AutomationHistoryStore.Location;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AutomationHistoryNavigationTest {
    @Test public void commentAndReplyUseOnlyVerifiedIdsAndOldWorksAreLabelledAsFallback() {
        Entry comment = entry("comment", "100", "sent").withLocation(new Location("1", "100", "200", "", "", ""));
        AutomationHistoryNavigation.Target target = AutomationHistoryNavigation.target(comment, true, "1");
        assertEquals("查看评论", target.label);
        assertEquals("200", target.uri.getQueryParameter("cid"));
        assertEquals("/detail/100", target.uri.getPath());
        assertTrue(AutomationHistoryNavigation.target(entry("comment", "100", "sent"), true, "1").hint.contains("未保存评论定位"));
        assertEquals("查看作品", AutomationHistoryNavigation.target(entry("comment", "100", "sent"), true, "1").label);
        Entry reply = entry("comment_reply", "100", "pending").withLocation(new Location("1", "100", "201", "200", "", ""));
        assertEquals("查看原评论", AutomationHistoryNavigation.target(reply, true, "1").label);
        assertEquals("201", AutomationHistoryNavigation.target(reply, true, "1").uri.getQueryParameter("cid"));
        Entry noReceipt = entry("comment_reply", "100", "sent")
                .withLocation(new Location("1", "100", "201", "200", "", "201"));
        assertEquals("查看原评论", AutomationHistoryNavigation.target(noReceipt, true, "1").label);
    }
    @Test public void accountMismatchOrMissingPrivateChatTypeCannotOpenTheWrongConversation() {
        Entry reply = entry("reply", "0:1:2:3", "sent")
                .withLocation(new Location("1", "", "", "", "0:1:2:3", "999", 0));
        assertTrue(AutomationHistoryNavigation.target(reply, true, "1").enabled());
        assertEquals("0:1:2:3", AutomationHistoryNavigation.target(reply, true, "1").uri.getQueryParameter("cid"));
        assertFalse(AutomationHistoryNavigation.target(reply, true, "2").enabled());
        assertFalse(AutomationHistoryNavigation.target(reply, false, "").enabled());
        assertFalse(AutomationHistoryNavigation.target(entry("reply", "0:1:2:3", "sent"), true, "1").enabled());
        assertFalse(AutomationHistoryNavigation.target(entry("comment", "100?cid=999", "sent"), true, "1").enabled());
        assertFalse(AutomationHistoryNavigation.target(entry("comment", "https://example.com", "sent"), true, "1").enabled());
    }
    private static Entry entry(String kind, String target, String status) {
        return new Entry("history", kind, target, "对象", "原文", "输出", "模型", status, 1, 1);
    }
}
