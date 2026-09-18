package com.matrix.agent.task.identity;
import com.matrix.agent.task.scheduler.*;

import com.matrix.agent.intent.KeywordIntentClassifier;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.Test;

/**
 * KeywordIntentClassifier 行为测试。
 *
 * <p>覆盖 4 个语义分支:只读 / 只写 / 同时含读写 / 未知,验证"未知按写处理"的保守契约。
 * 任意 case 返回 false 都意味着 TaskScheduler 不会抢占——保护车控写操作不被半路强制中断。
 */
public class KeywordIntentClassifierTest {

    private final KeywordIntentClassifier classifier = KeywordIntentClassifier.INSTANCE;

    @Test
    public void pureReadCommandReturnsTrue() {
        assertEquals(true, classifier.isReadOnly("查一下电量还剩多少"));
        assertEquals(true, classifier.isReadOnly("帮我查胎压状态"));
        assertEquals(true, classifier.isReadOnly("告诉我现在车速是什么状态"));
        assertEquals(true, classifier.isReadOnly("get battery status"));
    }

    @Test
    public void pureWriteCommandReturnsFalse() {
        assertEquals(false, classifier.isReadOnly("打开空调到 24 度"));
        assertEquals(false, classifier.isReadOnly("设座椅加热到 2 档"));
        assertEquals(false, classifier.isReadOnly("开始导航到公司"));
        assertEquals(false, classifier.isReadOnly("记住我喜欢的温度"));
        assertEquals(false, classifier.isReadOnly("set temperature 22"));
    }

    @Test
    public void mixedReadAndWriteReturnsFalse() {
        // 同时含读关键词"查"和写关键词"打开" → 模糊 → 按写处理
        assertEquals(false, classifier.isReadOnly("查一下空调然后打开"));
        assertEquals(false, classifier.isReadOnly("get status and save preference"));
    }

    @Test
    public void unknownCommandReturnsFalse() {
        // 完全无关键词 → 未知 → 按写处理(保守)
        assertEquals(false, classifier.isReadOnly("今天天气怎么样"));
        assertEquals(false, classifier.isReadOnly("play some music"));
    }

    @Test
    public void emptyOrNullCommandReturnsFalse() {
        assertEquals(false, classifier.isReadOnly(""));
        assertEquals(false, classifier.isReadOnly("   "));
        assertEquals(false, classifier.isReadOnly(null));
    }

    @Test
    public void protocolKeywordsUseRootLocaleInsteadOfDeviceLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            // Turkish default lowercasing turns LIST into l\u0131st. The protocol keyword is list.
            assertEquals(true, classifier.isReadOnly("LIST vehicle status"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
