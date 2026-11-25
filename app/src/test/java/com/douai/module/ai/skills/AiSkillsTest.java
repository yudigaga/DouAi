package com.douai.module.ai.skills;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AiSkillsTest {
    @Test
    public void bothEntriesOfferTheSameTextCommandsAndFilterPrefixes() {
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            assertEquals(List.of("摘要", "查询", "技能"),
                    AiSkills.commands(entry, "/").stream().map(AiSkills.Command::name).toList());
            assertEquals("查询", AiSkills.commands(entry, "/查").get(0).name());
            assertTrue(AiSkills.commands(entry, "/查询 ").isEmpty());
            assertTrue(AiSkills.commands(entry, "普通消息 /").isEmpty());
        }
    }

    @Test
    public void creationMenuDoesNotRegisterAnExecutableCommand() {
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            assertEquals(List.of("技能"), AiSkills.commands(entry, "/技").stream().map(AiSkills.Command::name).toList());
            assertNull(AiSkills.match(entry, "/技能"));
            assertNull(AiSkills.match(entry, "/技能 写一个润色技能"));
            assertTrue(AiSkills.commands(entry, "/技能 ").isEmpty());
        }
    }

    @Test
    public void onlyExactCommandsTakeOverOrdinaryChat() {
        assertNull(AiSkills.match(AiSkills.Entry.CONTACT, "/查询一下 123"));
        assertNull(AiSkills.match(AiSkills.Entry.WORK, "请 /查询 123"));
        assertEquals("123", AiSkills.match(AiSkills.Entry.WORK, "  /查询\t123  ").argument());
        assertThrows(IllegalArgumentException.class,
                () -> AiSkills.execute(AiSkills.match(AiSkills.Entry.WORK, "/查询")));
    }

    @Test
    public void builtInSkillsReturnSummaryAndLocalMockResponse() {
        String message = "/摘要 今天发布了测试版本。明天验证所有界面。下周收集反馈。";
        assertEquals("今天发布了测试版本。明天验证所有界面。",
                AiSkills.execute(AiSkills.match(AiSkills.Entry.CONTACT, message)));
        assertEquals("模拟查询：订单 DA-1001 已发货。",
                AiSkills.execute(AiSkills.match(AiSkills.Entry.WORK, "/查询 DA-1001")));
        assertEquals("模拟查询：未找到订单 unknown。",
                AiSkills.execute(AiSkills.match(AiSkills.Entry.CONTACT, "/查询 unknown")));
    }
}
