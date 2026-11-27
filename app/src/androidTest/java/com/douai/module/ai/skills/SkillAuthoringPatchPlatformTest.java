package com.douai.module.ai.skills;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class SkillAuthoringPatchPlatformTest {
    @Test public void referenceRequestTargetsOnlyCurrentVideoDraft() {
        assertTrue(SkillAuthoring.fillsCurrentVideoDraftOnly(
                "按上面的接口资料帮我填写当前视频技能，不要新建，也不要调用接口。"));
    }

    @Test public void conversationalEditUsesAndroidRegexAndPreservesTextAfterUrl() throws Exception {
        String prompt = "把描述改成更清晰";
        String document = "接口：https://sample.example.test/hidden\u3000保留文字，"
                + "参考：https://another.example.test/hidden\u00a0继续说明";

        assertEquals(Set.of("description"), SkillAuthoringPatch.targets(null, prompt));
        SkillAuthoringPatch.Request request = SkillAuthoringPatch.request(null, Set.of("description"),
                prompt, document, false);

        assertEquals("接口：[地址已省略]\u3000保留文字，参考：[地址已省略]\u00a0继续说明",
                new JSONObject(request.input()).getString("document"));
        assertEquals(Map.of("description", "直接说明技能的用途"), request.read("""
                {"version":1,"updates":[{"field":"description","value":"直接说明技能的用途","evidence":"把描述改成更清晰"}]}
                """));
    }
}
