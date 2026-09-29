package com.matrix.agent.task.prompt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PromptContextAssemblerSkillBudgetTest {
    @Test public void dropsMemoryBeforeTruncatingSignedSkill() {
        String base = "核心系统规则" + "\n已召回的 Memory" + "记忆".repeat(100);
        String skill = "\n<skill_guidance>切换前必须确认暂停</skill_guidance>";
        String result = PromptContextAssembler.appendSkillWithinBudget(base, skill, 100);
        assertEquals("核心系统规则" + skill, result);
        assertTrue(result.endsWith("</skill_guidance>"));
    }
}
