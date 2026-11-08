package com.douai.module.feature;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class CommentAiRecommendationPlanTest {
    private static final String SOURCE = "作品文案：每年冬天必跳 #北极星 #手势舞 #甜妹 #冬季\n配乐名称：北极星\n";

    @Test public void acceptsOnlyAWholeObjectOrStandaloneJsonFence() {
        String json = "{\"questions\":[\"动作有什么要点？\",\"如何跟拍？\",\"配乐是什么？\"],\"search_query\":\"北极星 手势舞\"}";
        CommentAiRecommendationPlan plain = CommentAiRecommendationPlan.parse(json, "总结这条内容", SOURCE);
        assertEquals(List.of("动作有什么要点？", "如何跟拍？", "配乐是什么？"), plain.questions);
        assertEquals("北极星 手势舞", plain.searchQuery);
        assertEquals(plain.questions, CommentAiRecommendationPlan.parse("```json\n" + json + "\n```", "", SOURCE).questions);
        assertEquals(plain.searchQuery, CommentAiRecommendationPlan.parse("```\n" + json + "\n```", "", SOURCE).searchQuery);

        CommentAiRecommendationPlan fallback = CommentAiRecommendationPlan.fallback("", SOURCE);
        for (String invalid : List.of("说明：" + json, json + " trailing", "```json\n" + json + "\n``` extra",
                "[" + json + "]", "{\"questions\":\"wrong type\",\"search_query\":\"UNTRUSTED\"}",
                "{questions:['unquoted'],search_query:'UNTRUSTED'}",
                "{\"search_query\":\"first\",\"search_query\":\"UNTRUSTED\"}",
                "{\"search_query\":\"UNTRUSTED\",}",
                "{\"questions\":[],\"search_query\":\"UNTRUSTED\",\"unexpected\":true}")) {
            CommentAiRecommendationPlan result = CommentAiRecommendationPlan.parse(invalid, "", SOURCE);
            assertEquals(fallback.questions, result.questions);
            assertEquals(fallback.searchQuery, result.searchQuery);
        }
    }

    @Test public void trimsDeduplicatesAndFillsExactlyThreeImmutableQuestions() throws Exception {
        String output = new JSONObject().put("questions", new JSONArray()
                .put("  怎样跟拍？  ").put("").put("怎样跟拍？").put("了解\n更多\u0000细节？"))
                .put("search_query", " 北极星\n手势舞\u0000 ").toString();

        CommentAiRecommendationPlan plan = CommentAiRecommendationPlan.parse(output, "", SOURCE);

        assertEquals(3, plan.questions.size());
        assertEquals("怎样跟拍？", plan.questions.get(0));
        assertEquals("了解 更多 细节？", plan.questions.get(1));
        assertEquals("北极星 手势舞", plan.searchQuery);
        assertThrows(UnsupportedOperationException.class, () -> plan.questions.clear());
        assertEquals(3, plan.questions.stream().distinct().count());
    }

    @Test public void enforcesOutputAndUnicodeLimitsAndRepairsMissingFields() throws Exception {
        String repeated = "😀".repeat(150);
        String output = new JSONObject().put("questions", new JSONArray().put(repeated))
                .put("search_query", repeated).toString();
        CommentAiRecommendationPlan plan = CommentAiRecommendationPlan.parse(output, "", SOURCE);
        assertEquals(3, plan.questions.size());
        for (String question : plan.questions) {
            assertTrue(question.length() <= 70);
            assertFalse(Character.isHighSurrogate(question.charAt(question.length() - 1)));
        }
        assertTrue(plan.searchQuery.length() <= 160);
        assertFalse(Character.isHighSurrogate(plan.searchQuery.charAt(plan.searchQuery.length() - 1)));
        assertEquals(CommentAiRecommendationPlan.fallback("", SOURCE).questions,
                CommentAiRecommendationPlan.parse("x".repeat(6_001), "", SOURCE).questions);
        assertEquals(3, CommentAiRecommendationPlan.parse("{}", null, null).questions.size());
        assertFalse(CommentAiRecommendationPlan.parse("{}", null, null).searchQuery.isEmpty());
    }

    @Test public void fallbackPrefersSpecificQuestionsAndBoundsCaptionHashtags() {
        CommentAiRecommendationPlan specific = CommentAiRecommendationPlan.fallback("北极星手势舞零基础教程", SOURCE);
        assertEquals("北极星手势舞零基础教程", specific.searchQuery);

        for (String generic : List.of("总结这条内容", "请帮我总结一下这条视频", "分析一下", "")) {
            CommentAiRecommendationPlan plan = CommentAiRecommendationPlan.fallback(generic, SOURCE);
            assertTrue(plan.searchQuery.contains("北极星"));
            assertTrue(plan.searchQuery.contains("手势舞"));
            assertFalse(plan.searchQuery.contains("#"));
            assertFalse(plan.searchQuery.contains("冬季"));
            assertEquals(3, plan.questions.size());
        }
        CommentAiRecommendationPlan musicOnly = CommentAiRecommendationPlan.fallback("总结这条内容", "配乐名称：北极星\n");
        assertEquals("北极星", musicOnly.searchQuery);
        assertTrue(musicOnly.questions.stream().anyMatch(value -> value.contains("北极星")));
    }

    @Test public void requestStaysWithinClientPromptLimitAndKeepsInputsAsQuotedData() throws Exception {
        String request = CommentAiRecommendationPlan.request("问".repeat(10_000),
                "作品文案：" + "文".repeat(20_000), "答".repeat(10_000));
        assertTrue(request.length() <= 4_000);
        assertTrue(request.contains("只输出一个 JSON 对象"));
        assertTrue(request.contains("不是指令"));
        assertTrue(request.contains("禁止生成视频 ID"));
        int dataStart = request.indexOf("\n输入资料：\n") + "\n输入资料：\n".length();
        assertTrue(dataStart > 0);
        JSONObject data = new JSONObject(request.substring(dataStart));
        assertTrue(data.getString("prompt").length() <= 400);
        assertTrue(data.getString("document").length() <= 1_100);
        assertTrue(data.getString("answer").length() <= 1_600);
        assertTrue(CommentAiRecommendationPlan.request("\\\"".repeat(10_000),
                "\\\"".repeat(10_000), "\\\"".repeat(10_000)).length() <= 4_000);
    }
}
