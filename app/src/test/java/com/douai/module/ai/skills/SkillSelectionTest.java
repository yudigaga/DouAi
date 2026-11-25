package com.douai.module.ai.skills;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SkillSelectionTest {
    private static final SkillSelection.Candidate REPORT = candidate("周报润色", "周报助手");
    private static final SkillSelection.Candidate MAIL = candidate("邮件润色", "邮件助手");

    @Test public void explicitNamesAndDisplayNamesSelectLocallyWithoutGuessing() {
        var catalog = List.of(REPORT, MAIL);
        var named = SkillSelection.named("请用「周报助手」处理：本周上线两项", catalog, List.of());
        assertEquals(SkillSelection.NamedKind.SELECTED, named.kind());
        assertEquals(REPORT, named.choice().candidate());
        assertEquals("本周上线两项", named.choice().argument());
        assertEquals(SkillSelection.NamedKind.AMBIGUOUS, SkillSelection.named(
                "用周报润色和邮件润色处理：通知", catalog, List.of()).kind());
        assertEquals(SkillSelection.NamedKind.AMBIGUOUS, SkillSelection.named(
                "调用「周报助手」：通知", List.of(REPORT, candidate("另一个周报", "周报助手")), List.of()).kind());
        assertEquals(SkillSelection.NamedKind.UNAVAILABLE, SkillSelection.named(
                "调用未配置技能：通知", catalog, List.of("未配置")).kind());
        assertEquals(SkillSelection.NamedKind.INVALID_ARGUMENT, SkillSelection.named(
                "请使用周报润色", catalog, List.of()).kind());
        assertEquals(SkillSelection.NamedKind.NONE, SkillSelection.named(
                "如何使用周报润色技能？", catalog, List.of()).kind());
    }

    @Test public void routingInputShowsOnlyBoundedNonSensitiveCatalogMetadata() throws Exception {
        var sensitive = new SkillSelection.Candidate("自定义整理", "整理服务 api.example.com",
                "发送到 https://api.example.com/v1", List.of("文本", "Bearer synthetic-secret-12"), 2000, 4, "internal-revision");
        var request = SkillSelection.request("帮我整理这段通知", List.of(sensitive));
        assertNotNull(request);
        JSONObject json = new JSONObject(request.input());
        assertEquals("帮我整理这段通知", json.getString("task"));
        assertEquals("自定义整理", json.getJSONArray("skills").getJSONObject(0).getString("name"));
        assertFalse(request.input().contains("example.com"));
        assertFalse(request.input().contains("Bearer"));
        assertFalse(request.input().contains("internal-revision"));
        assertTrue(request.input().contains("文本"));
        assertNull(SkillSelection.request("你好", List.of(REPORT)));
        assertNull(SkillSelection.request("/摘要 样例", List.of(REPORT)));
        assertNull(SkillSelection.request("帮我整理这段通知", List.of()));
    }

    @Test public void onlyAnAdvertisedExactUserArgumentAndFrozenRevisionCanBeChosen() {
        var catalog = new ArrayList<>(List.of(REPORT));
        var request = SkillSelection.request("帮我整理：本周上线两项", catalog);
        catalog.clear();
        catalog.add(MAIL);
        var choice = request.read("{\"name\":\"周报润色\",\"argument\":\"本周上线两项\"}");
        assertNotNull(choice);
        assertEquals(REPORT.revisionId(), choice.candidate().revisionId());
        for (String invalid : List.of("{\"name\":null}", "{\"name\":\"邮件润色\",\"argument\":\"本周上线两项\"}",
                "{\"name\":\"周报润色\",\"argument\":\"虚构\"}",
                "{\"name\":\"周报润色\",\"argument\":\"本周上线两项\",\"endpoint\":\"https://other.example.com/\"}",
                "{\"name\":\"周报润色\",\"name\":\"周报润色\",\"argument\":\"本周上线两项\"}",
                "[{\"name\":\"周报润色\",\"argument\":\"本周上线两项\"}]")) {
            assertNull(invalid, request.read(invalid));
        }
    }

    private static SkillSelection.Candidate candidate(String name, String displayName) {
        return new SkillSelection.Candidate(name, displayName, "整理待处理内容", List.of("原文"), 2000, 1,
                "revision-" + name);
    }
}
