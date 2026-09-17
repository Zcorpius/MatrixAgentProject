package com.matrix.agent.task.identity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * KeywordMemoryIntentDetector 关键词匹配测试。
 *
 * <p>用户硬约束——"长期记忆仅由用户决定"。Detector 在 Repository.execute 入口判定
 * 用户原文是否含显式记忆动词(记住/保存/以后默认/不要忘记/别忘了/默认用/长期/永远用)。
 * 命中 → memorySaveAllowed=true,memory.semantic.save handler 放行;
 * 未命中 → false,handler 直接 POLICY_REJECTED。
 *
 * <p>否定短语门(用户硬约束——"不要记住 不应作为安全边界的可接受行为")。
 * 含 "不要记住 / 别记住 / 无需保存 / 不用保存 / 不要长期保存 / 删除记忆" 任一 → 优先短路
 * 返回 false,即便命令同时含正向关键词(如"不要记住"含"记住")。
 *
 * <p>验证:
 * <ul>
 *   <li>正面 8 例:命中关键词(覆盖 8 个关键词)→ true</li>
 *   <li>负面 6 例:纯查询 / 车控写 / 闲聊,无显式记忆意图 → false</li>
 *   <li>否定 6 例:含否定短语 → false(即便同时含"记住"/"保存")</li>
 *   <li>null / empty 安全 → false</li>
 *   <li>大小写不敏感</li>
 * </ul>
 *
 * <p>已知限制(仍接受,后续版本升 LLM-based):
 * 同义词("给我存下来"/"记牢")、英文 / 方言 / 多语言未覆盖。
 */
public final class KeywordMemoryIntentDetectorTest {

    private final KeywordMemoryIntentDetector detector = KeywordMemoryIntentDetector.INSTANCE;

    @Test
    public void positive_rememberAllergy() {
        assertTrue(detector.isExplicitMemorySave("记住我对花生过敏"));
    }

    @Test
    public void positive_saveHomeAddress() {
        assertTrue(detector.isExplicitMemorySave("保存我的家庭地址"));
    }

    @Test
    public void positive_defaultTemperature() {
        assertTrue(detector.isExplicitMemorySave("以后默认 24 度"));
    }

    @Test
    public void positive_doNotForgetBirthday() {
        assertTrue(detector.isExplicitMemorySave("不要忘记我女儿生日"));
    }

    @Test
    public void positive_dontForgetDestination() {
        assertTrue(detector.isExplicitMemorySave("别忘了我的常用目的地"));
    }

    @Test
    public void positive_defaultUseSportMode() {
        assertTrue(detector.isExplicitMemorySave("默认用运动模式"));
    }

    @Test
    public void positive_longTermPreference() {
        assertTrue(detector.isExplicitMemorySave("长期都用舒适模式"));
    }

    @Test
    public void positive_foreverUseNavVoice() {
        assertTrue(detector.isExplicitMemorySave("永远用女声导航"));
    }

    @Test
    public void negative_queryWeather() {
        assertFalse(detector.isExplicitMemorySave("今天天气怎么样"));
    }

    @Test
    public void negative_openAirConditioner() {
        assertFalse(detector.isExplicitMemorySave("打开空调"));
    }

    @Test
    public void negative_raiseTemperature() {
        assertFalse(detector.isExplicitMemorySave("调高温度"));
    }

    @Test
    public void negative_queryDate() {
        assertFalse(detector.isExplicitMemorySave("今天几号"));
    }

    @Test
    public void negative_stopCar() {
        assertFalse(detector.isExplicitMemorySave("停下车"));
    }

    @Test
    public void negative_playMusic() {
        assertFalse(detector.isExplicitMemorySave("我要听音乐"));
    }

    // 否定短语门——优先短路返回 false。

    @Test
    public void negative_doNotRemember() {
        // 含"记住"但语义为拒绝——NEGATIVE 短路在 POSITIVE 之前
        assertFalse(detector.isExplicitMemorySave("不要记住我女儿的名字"));
    }

    @Test
    public void negative_doNotRememberVariant() {
        assertFalse(detector.isExplicitMemorySave("别记住这次导航"));
    }

    @Test
    public void negative_noNeedToSave() {
        // 含"保存"但语义为拒绝——NEGATIVE 短路在 POSITIVE 之前
        assertFalse(detector.isExplicitMemorySave("无需保存这条偏好"));
    }

    @Test
    public void negative_noNeedToSaveVariant() {
        assertFalse(detector.isExplicitMemorySave("不用保存到记忆"));
    }

    @Test
    public void negative_doNotSaveLongTerm() {
        // 含"长期"+"保存"双正向词,但语义为拒绝——NEGATIVE 短路
        assertFalse(detector.isExplicitMemorySave("不要长期保存我的位置"));
    }

    @Test
    public void negative_deleteMemory() {
        assertFalse(detector.isExplicitMemorySave("删除记忆里的家庭地址"));
    }

    @Test
    public void nullCommandReturnsFalse() {
        assertFalse(detector.isExplicitMemorySave(null));
    }

    @Test
    public void emptyCommandReturnsFalse() {
        assertFalse(detector.isExplicitMemorySave(""));
        assertFalse(detector.isExplicitMemorySave("   "));
    }

    @Test
    public void caseInsensitiveMatch() {
        // 中文无大小写差异,但验证 trim/toLowerCase 路径不破坏匹配
        assertTrue(detector.isExplicitMemorySave("  记住我对花生过敏  "));
    }
}
