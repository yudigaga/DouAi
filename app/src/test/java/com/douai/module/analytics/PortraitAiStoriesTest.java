package com.douai.module.analytics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
public class PortraitAiStoriesTest {
    private JSONArray evidence() throws Exception {
        return new JSONArray().put(new JSONObject().put("id","topic:persistent")
            .put("type","topic").put("title","摄影贯穿这几天")
            .put("body","摄影出现 12 次，分布在 6 个浏览日。")
            .put("comparison","另一话题集中在 1 天。")
            .put("coverage",new JSONArray().put("接触不等于喜欢。")));
    }
    private String reply(String id,String explanation) throws Exception {
        return new JSONObject().put("version",2).put("discoveries",new JSONArray().put(
            new JSONObject().put("evidenceId",id).put("explanation",explanation)
                .put("alternative","也可能和推荐内容变化有关。")
                .put("question","这些内容和你的使用目的有关吗？"))).toString();
    }
    private String normalize(String r) throws Exception {
        return (String)Class.forName("com.douai.module.analytics.PortraitAiStories")
            .getDeclaredMethod("normalize",String.class,JSONArray.class).invoke(null,r,evidence());
    }
    @Test public void validatedProseKeepsLocalNumeratorAndCanBeShared() throws Exception {
        AnalyticsAiAnalysis.Result result=AnalyticsAiAnalysis.parseResult(normalize(reply("topic:persistent","这是分散在多天的内容接触。")));
        assertTrue(result.shareText().contains("12 次"));
        assertTrue(result.shareText().contains("6 个浏览日"));
        assertTrue(result.shareText().contains("分散在多天"));
        assertTrue(result.shareText().contains("接触不等于喜欢"));
    }
    @Test public void inventedNumbersOrWrongEvidenceFallbackToLocalText() throws Exception {
        for(String r:new String[]{reply("topic:persistent","你看了 999 次。"),reply("other-week","未核对的结论"),reply("topic:persistent","看了九十九次。"),reply("topic:persistent","一半的浏览会触发保存。"),reply("topic:persistent","保存比例翻倍。")}) {
            String shared=AnalyticsAiAnalysis.parseResult(normalize(r)).shareText();
            assertTrue(shared.contains("12 次")); assertTrue(shared.contains("本地依据"));
            assertFalse(shared.contains("999")); assertFalse(shared.contains("未核对的结论"));
            assertFalse(shared.contains("九十九次"));
        }
    }
    @Test public void longModelProseCannotHideLaterLocalFacts() throws Exception {
        JSONArray facts=evidence();
        facts.put(new JSONObject(facts.getJSONObject(0).toString()).put("id","second").put("body","第二条本地依据 33/100。"));
        facts.put(new JSONObject(facts.getJSONObject(0).toString()).put("id","third").put("body","第三条本地依据 44/200。"));
        JSONArray rows=new JSONArray();
        for(int i=0;i<facts.length();i++) rows.put(new JSONObject().put("evidenceId",facts.getJSONObject(i).getString("id"))
            .put("explanation","这可能反映内容接触方式的差异。".repeat(8)).put("alternative","也可能由推荐导致。").put("question","与你的感受相符吗？"));
        String reply=new JSONObject().put("version",2).put("discoveries",rows).toString();
        String canonical=(String)Class.forName("com.douai.module.analytics.PortraitAiStories").getDeclaredMethod("normalize",String.class,JSONArray.class).invoke(null,reply,facts);
        String text=AnalyticsAiAnalysis.parseResult(canonical).shareText();
        assertTrue(text.contains("33/100")); assertTrue(text.contains("44/200")); assertTrue(text.contains("接触不等于喜欢"));
    }
    @Test public void saveRateDecompositionIsNotABrowsingTrend() throws Exception {
        JSONObject fact=evidence().getJSONObject(0).put("id","period").put("type","change")
                .put("body","保存入口从14%到22%，相差8个百分点。");
        AnalyticsAiAnalysis.Result result=AnalyticsAiAnalysis.parseResult(PortraitAiStories.normalize(
                reply("period","内容构成发生了变化。"),new JSONArray().put(fact)));
        assertTrue(result.contrasts.contains("相差8个百分点"));assertFalse(result.trend.contains("相差8个百分点"));
    }

    @Test public void sharedActionFindingNamesItsSubjectEvenAsTheSecondFinding() throws Exception {
        class DayClock extends java.time.Clock {
            java.time.Instant now=java.time.Instant.parse("2026-11-01T00:00:00Z");
            public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
            public java.time.Clock withZone(java.time.ZoneId z){return java.time.Clock.fixed(now,z);}
            public java.time.Instant instant(){return now;}
        }
        DayClock clock=new DayClock();
        java.io.File root=java.nio.file.Files.createTempDirectory(org.robolectric.RuntimeEnvironment.getApplication().getCacheDir().toPath(),"share-facts").toFile();
        try(LocalAnalytics db=new LocalAnalytics(root,clock)){
            db.setEnabled(true);
            String warm=db.beginVisit(db.generation(),"warm","image",null,"",java.util.List.of(),false,0,"manual");
            db.recordAction(db.generation(),warm,"warm","copy_text","复制文案","manual","success");
            db.snapshot(1);
            for(int d=0;d<3;d++){
                clock.now=java.time.Instant.parse("2026-11-07T12:00:00Z").plus(java.time.Duration.ofDays(d));
                for(int i=0;i<16;i++){
                    String id="session-"+d+"-"+i;
                    String token=db.beginVisit(db.generation(),id,"image",null,"",java.util.List.of("摄影",i<8?"露营":"骑行"),false,0,"manual");
                    if(d==0&&(i<4||i==8))db.recordAction(db.generation(),token,id,"copy_text","复制文案","manual","success");
                }
            }
            JSONArray sent=PortraitAiStories.evidence(db.snapshot(7).portrait.evidence);
            assertEquals("topic",sent.getJSONObject(0).getString("type"));
            assertEquals("action",sent.getJSONObject(1).getString("type"));
            String emptyModel=new JSONObject().put("version",2).put("discoveries",new JSONArray()).toString();
            AnalyticsAiAnalysis.Result result=AnalyticsAiAnalysis.parseResult(PortraitAiStories.normalize(emptyModel,sent));
            for(String word:new String[]{"露营","复制","4/24","1/24","片段","不含"})assertTrue(result.features+" missing "+word,result.features.contains(word));
            assertTrue(result.shareText().contains(result.features));
        }
    }

    @Test public void lengthyLocalFactsRemainIntactInTheirOwnSections() throws Exception {
        JSONArray facts = new JSONArray();
        String[] types={"topic","action","change"}, ends={"12/12 浏览日。","4/24 对 1/24 个片段。","增加 8 个百分点。"};
        for (int i=0;i<types.length;i++) {
            facts.put(new JSONObject().put("id",types[i]).put("type",types[i]).put("title","本地发现")
                .put("body","带较长名称的话题".repeat(25)+ends[i]).put("comparison","仅比较保留样本")
                .put("coverage",new JSONArray().put("当前时间范围的保留记录，不能推断实际观看。".repeat(4))));
        }
        AnalyticsAiAnalysis.Result result=AnalyticsAiAnalysis.parseResult(PortraitAiStories.normalize(
            new JSONObject().put("version",2).put("discoveries",new JSONArray()).toString(),facts));
        assertTrue(result.topics.contains(ends[0]));assertTrue(result.features.contains(ends[1]));
        assertTrue(result.contrasts.contains(ends[2]));assertTrue(result.portrait.length()<=600);
    }

    @Test public void exactLongTopicPairIdIsNotSubjectToTheProseLengthLimit() throws Exception {
        String id="topic:contrast:topic:"+"甲".repeat(80)+":topic:"+"乙".repeat(80);
        JSONArray facts=evidence();facts.getJSONObject(0).put("id",id);
        String normalized=PortraitAiStories.normalize(reply(id,"这个话题出现得更分散。"),facts);
        assertTrue(AnalyticsAiAnalysis.parseResult(normalized).topics.contains("这个话题出现得更分散。"));
    }

}
