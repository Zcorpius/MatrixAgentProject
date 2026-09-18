package com.matrix.agent.task;
import com.matrix.agent.host.di.*;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * AgentBudget 9 参构造器(token 维度显式注入)测试。
 *
 * <p>旧版 char/4 估算的行为由 {@link AgentBudgetTokenApiTest} 覆盖(零回归);
 * 切真实 jtokkit token 时,AppContainer 通过 9 参构造器显式注入 token 维度,
 * 不再派生 char/4。
 *
 * <p>本测试验证 9 参构造器独立字段——传 token 值与 char/4 不一致时仍按显式值返回。
 */
public final class AgentBudgetTokenExplicitConstructorTest {

    @Test
    public void explicitTokenFieldsAreReadAsIs() {
        AgentBudget budget = new AgentBudget(
                8, 8, 60_000L,
                8_000, 32_000, 64,
                2_048, 8_192, 1_500);

        assertEquals("maxMessageTokens 显式值", 2_048, budget.getMaxMessageTokens());
        assertEquals("totalInputTokens 显式值", 8_192, budget.getTotalInputTokens());
        assertEquals("maxAssistantTokens 显式值", 1_500, budget.getMaxAssistantTokens());
    }

    @Test
    public void explicitTokenFieldsDecoupleFromCharBudget() {
        // char/4 ≠ token 字段——验证字段独立(不派生)
        AgentBudget budget = new AgentBudget(
                8, 8, 60_000L,
                8_000, 32_000, 64,
                1, 1, 1);

        assertEquals("maxMessageChars 与 token 字段解耦", 8_000, budget.getMaxMessageChars());
        assertEquals("totalInputChars 与 token 字段解耦", 32_000, budget.getTotalInputChars());
        assertEquals("maxMessageTokens 不从 char 派生", 1, budget.getMaxMessageTokens());
        assertEquals("totalInputTokens 不从 char 派生", 1, budget.getTotalInputTokens());
        assertEquals("maxAssistantTokens 显式 1", 1, budget.getMaxAssistantTokens());
    }

    @Test
    public void legacySixArgConstructorStillDerivesTokenFromCharDivideByFour() {
        // 6 参构造器(旧版主路径)仍走 char/4 派生——AgentBudgetTokenApiTest 零回归
        AgentBudget budget = new AgentBudget(4, 4, 30_000L, 4_000, 16_000, 32);

        assertEquals(1_000, budget.getMaxMessageTokens());
        assertEquals(4_000, budget.getTotalInputTokens());
        assertEquals(AgentBudget.DEFAULT_MAX_ASSISTANT_TOKENS, budget.getMaxAssistantTokens());
    }

    @Test(expected = IllegalArgumentException.class)
    public void explicitTokenConstructorRejectsZeroMaxMessageTokens() {
        new AgentBudget(8, 8, 60_000L, 8_000, 32_000, 64, 0, 8_192, 1_024);
    }

    @Test(expected = IllegalArgumentException.class)
    public void explicitTokenConstructorRejectsZeroTotalInputTokens() {
        new AgentBudget(8, 8, 60_000L, 8_000, 32_000, 64, 2_048, 0, 1_024);
    }

    @Test(expected = IllegalArgumentException.class)
    public void explicitTokenConstructorRejectsZeroMaxAssistantTokens() {
        new AgentBudget(8, 8, 60_000L, 8_000, 32_000, 64, 2_048, 8_192, 0);
    }
}
