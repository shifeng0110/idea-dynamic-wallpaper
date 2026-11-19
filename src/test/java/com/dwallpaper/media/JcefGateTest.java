package com.dwallpaper.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCEF 降级路径验证（AC-MEDIA-4 静态部分，任务口径第 1/5 条）：
 * 纯 JVM（无 com.intellij.ui.jcef classpath）必须判定不可用、原因明确、状态行含回退口径。
 */
class JcefGateTest {

    @Test
    void pureJvmWithoutJcefMustDegrade() {
        JcefGate.resetForTest();
        try {
            assertFalse(JcefGate.isJcefUsable(),
                    "测试 JVM 无 JCEF 类，isJcefUsable 必须为 false（降级路径）");
            String reason = JcefGate.unavailableReason();
            assertNotNull(reason, "不可用原因必须输出");
            assertTrue(reason.contains("缺失") || reason.contains("isSupported"),
                    "原因须明确指向类缺失或 isSupported，实际=" + reason);
            String state = JcefGate.describeState();
            assertTrue(state.contains("回退"), "状态描述须含回退口径，实际=" + state);
            assertTrue(state.contains("GIF"), "回退口径须点名 t4 能力（GIF/序列帧/静态），实际=" + state);
        } finally {
            JcefGate.resetForTest();
        }
    }
}
