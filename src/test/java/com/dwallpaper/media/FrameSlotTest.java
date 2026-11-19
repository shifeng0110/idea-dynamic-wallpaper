package com.dwallpaper.media;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单槽帧缓存语义（design.md §4.3「新帧覆盖旧帧」）+ 内存上限 + F14 借用隔离。
 * F14 核心不变量：pollFrame 交付过的缓冲永不回池复用（pipeline.lastFrame 长期持有的缓冲
 * 绝无并发写入 → recompute 视觉撕裂消除）；未交付缓冲（消费者从未见过）可安全回池复用。
 */
class FrameSlotTest {

    private static void fill(BufferedImage img, int argb) {
        int[] raster = java.awt.image.DataBufferInt.class.cast(img.getRaster().getDataBuffer()).getData();
        java.util.Arrays.fill(raster, argb);
    }

    private static int pixel(BufferedImage img, int x, int y) {
        return img.getRGB(x, y);
    }

    @Test
    void pollReturnsNullWhenNoNewFrame() {
        FrameSlot slot = new FrameSlot();
        assertNull(slot.pollFrame(), "无新帧必须返回 null（FrameSource 契约）");
    }

    @Test
    void publishThenPollReturnsFrameThenNull() {
        FrameSlot slot = new FrameSlot();
        BufferedImage img = slot.acquire(4, 4);
        assertNotNull(img);
        slot.publish(img);
        com.dwallpaper.core.Frame frame = slot.pollFrame();
        assertNotNull(frame);
        assertEquals(4, frame.sourceWidth());
        assertEquals(4, frame.sourceHeight());
        assertEquals(img, frame.image());
        assertNull(slot.pollFrame(), "同帧二次 poll 必须返回 null（无新帧）");
        assertEquals(1, slot.getFramesPublished());
    }

    @Test
    void overCapFrameDropped() {
        FrameSlot slot = new FrameSlot(64); // 64 字节
        assertNull(slot.acquire(8, 8), "8x8 ARGB=256B > 64B 必须丢帧");
        assertEquals(1, slot.getFramesDropped());
        BufferedImage ok = slot.acquire(4, 4); // 4x4=64B == 上限
        assertNotNull(ok);
        assertEquals(1, slot.getFramesDropped(), "成功的 acquire 不再累计丢帧计数");
    }

    @Test
    void zeroOrNullSizeRejected() {
        FrameSlot slot = new FrameSlot();
        assertNull(slot.acquire(0, 10));
        assertNull(slot.acquire(10, 0));
        assertNull(slot.acquire(-1, 5));
    }

    /**
     * F14 核心证明：已交付缓冲隔离（永不回池写入），未交付缓冲安全复用。
     * 场景对应生产路径：a 交付后由 pipeline.lastFrame 长期持有；其后 CEF 继续产帧，
     * 任何 acquire 都不得拿到 a；对后续缓冲的写入不得影响 a 的像素（视觉撕裂消除）。
     */
    @Test
    void deliveredBufferIsolatedAndUndeliveredReused() {
        FrameSlot slot = new FrameSlot();
        int patternA = 0xFF0000FF; // 红蓝标记（ARGB）
        int patternC = 0xFF00FF00; // 绿色标记

        BufferedImage a = slot.acquire(4, 4);
        fill(a, patternA);
        slot.publish(a);
        com.dwallpaper.core.Frame first = slot.pollFrame();
        assertNotNull(first);
        assertSame(a, first.image(), "交付帧必须是 a");

        // a 已交付；池空 → 新建 b（不得复用 a）
        BufferedImage b = slot.acquire(4, 4);
        assertNotSame(a, b, "已交付缓冲不得复用");

        // publish b：覆盖 a（已交付）→ a 进隔离区，不回池
        slot.publish(b);
        assertEquals(0, slot.getPoolSize(), "已交付缓冲不得回池");

        // 再申请 c：仍不得拿到 a；对 c 写入不得影响 a 的像素（模拟 pipeline.lastFrame 持有 a 期间 CEF 写新帧）
        BufferedImage c = slot.acquire(4, 4);
        assertNotSame(a, c, "F14：已交付缓冲永不复用");
        fill(c, patternC);
        assertEquals(patternA, pixel(a, 2, 2), "交付帧像素不受后续 CEF 写入影响（视觉撕裂消除）");

        // publish c：覆盖 b（未交付，消费者从未见过）→ b 回池复用（安全）
        slot.publish(c);
        assertEquals(1, slot.getPoolSize(), "未交付缓冲应回池");
        BufferedImage d = slot.acquire(4, 4);
        assertSame(b, d, "未交付缓冲可安全回池复用");
        assertEquals(1, slot.getQuarantineSize(), "隔离区恰持有 a");
    }

    /**
     * F14：连续交付多帧（快速轮转 + 隔离区上限）下，任何 acquire 都不得返回历史交付过的缓冲。
     */
    @Test
    void deliveredBuffersNeverRecycledAcrossRapidRotation() {
        FrameSlot slot = new FrameSlot();
        Set<BufferedImage> delivered = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            BufferedImage img = slot.acquire(4, 4);
            assertNotNull(img);
            assertFalse(delivered.contains(img), "acquire 不得返回任何历史交付缓冲（轮次 " + i + "）");
            slot.publish(img);
            com.dwallpaper.core.Frame f = slot.pollFrame();
            assertNotNull(f);
            delivered.add(f.image());
            assertTrue(slot.getQuarantineSize() <= FrameSlot.DELIVERED_QUARANTINE_LIMIT,
                    "隔离区引用数不得超上限（超出弃置引用交 GC）");
        }
        // 隔离区上限弃置引用后，被弃置缓冲也不得回池：再申请必须拿全新或未交付池内缓冲
        BufferedImage next = slot.acquire(4, 4);
        assertFalse(delivered.contains(next), "隔离引用弃置后缓冲也不得复用");
    }

    /**
     * F14 未交付路径的池约束：无消费（不 poll）时旧 current 均未交付 → 回池，池不超上限；
     * 池满时新缓冲仍可分配（不因池满丢帧）。
     */
    @Test
    void undeliveredPoolStaysBounded() {
        FrameSlot slot = new FrameSlot();
        for (int i = 0; i < 10; i++) {
            slot.publish(slot.acquire(4, 4)); // 不 poll：旧 current 全部未交付 → 回池
        }
        assertTrue(slot.getPoolSize() <= FrameSlot.FRAME_POOL_BUFFERS, "池不得超上限");
        assertNotNull(slot.acquire(4, 4), "池满时新缓冲仍可分配");
        assertEquals(0, slot.getFramesDropped(), "正常轮转不应触发内存上限丢帧");
    }

    @Test
    void newFrameIdsAreMonotonic() {
        FrameSlot slot = new FrameSlot();
        slot.publish(slot.acquire(2, 2));
        long id1 = slot.pollFrame().frameId();
        slot.publish(slot.acquire(2, 2));
        long id2 = slot.pollFrame().frameId();
        assertTrue(id2 > id1, "frameId 必须单调递增");
    }

    @Test
    void closeClearsState() {
        FrameSlot slot = new FrameSlot();
        slot.publish(slot.acquire(2, 2));
        slot.close();
        assertNull(slot.pollFrame());
        assertEquals(0, slot.getPoolSize());
        assertEquals(0, slot.getQuarantineSize());
    }
}
