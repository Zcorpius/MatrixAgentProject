package com.matrix.agent.task;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * AgentBudget token 维度 getter 测试。
 *
 * <p>token 维度不参与 enforcement——主路径仍用 char 判断。
 * 本测试只验证 getter 返回值符合保守估算(char / {@link AgentBudget#CHAR_PER_TOKEN_FALLBACK})。
 * 切 JtokkitTokenizer 主路径后,本测试改为 token-based 预算回归。
 */
public final class AgentBudgetTokenApiTest {
    @Test
    public void defaultBudgetTokenGettersAreCharDividedBy4() {
        AgentBudget budget = new AgentBudget();
        assertEquals(AgentBudget.DEFAULT_MAX_MESSAGE_CHARS / AgentBudget.CHAR_PER_TOKEN_FALLBACK,
                budget.getMaxMessageTokens());
        assertEquals(AgentBudget.DEFAULT_TOTAL_INPUT_CHARS / AgentBudget.CHAR_PER_TOKEN_FALLBACK,
                budget.getTotalInputTokens());
        assertEquals(AgentBudget.DEFAULT_MAX_ASSISTANT_TOKENS, budget.getMaxAssistantTokens());
    }

    @Test
    public void customBudgetTokenGettersScaleWithCharBudget() {
        AgentBudget budget = new AgentBudget(4, 4, 30_000L,
                4_000, 16_000, 32);
        assertEquals(1_000, budget.getMaxMessageTokens());
        assertEquals(4_000, budget.getTotalInputTokens());
        assertEquals(AgentBudget.DEFAULT_MAX_ASSISTANT_TOKENS, budget.getMaxAssistantTokens());
    }

    @Test
    public void verySmallCharBudgetStillReturnsAtLeastOneToken() {
        AgentBudget budget = new AgentBudget(1, 1, 1L, 1, 1, 1);
        assertTrue("maxMessageTokens 最小为 1", budget.getMaxMessageTokens() >= 1);
        assertTrue("totalInputTokens 最小为 1", budget.getTotalInputTokens() >= 1);
    }
}
