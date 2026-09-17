package com.matrix.agent.voice.platform;

import com.matrix.agent.voice.platform.VoiceCaptureController.FrameDispatcher;
import com.matrix.agent.voice.platform.VoiceCaptureController.FrameReader;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link VoiceCaptureController} JVM 可测部分:采音循环核心 {@link VoiceCaptureController#driveFrames}。
 *
 * <p>P1/P2 回归:stop() 与阻塞 read()/端口分发的并发窗口——
 * stop 打断 read 的负码、stop 后返回的正帧、停止中端口抛异常,都必须归为正常 STOPPED
 * (不分发、不计故障预算、不误报 CAPTURE_PIPELINE_ERROR),否则正常 pause → resume 会
 * 计入 Runtime 故障预算触发失败重试。reader/dispatcher 内改 running 模拟"恰好并发"时序。
 */
public final class VoiceCaptureControllerTest {

    @Test
    public void stopInterruptedRead_negativeCode_isNormalStop() {
        // read 返回负码时 stop() 已置 running=false → STOPPED(不计故障预算、不失败重试)
        assertNull(VoiceCaptureController.classifyReadError(-3, false));
        assertNull(VoiceCaptureController.classifyReadError(AudioReadCodes.DEAD_OBJECT, false));
        // 循环级:reader 抛出前 stop() 已发生 → null(STOPPED),dispatcher 不被调用
        AtomicBoolean running = new AtomicBoolean(true);
        List<Integer> dispatched = new ArrayList<>();
        String reason = VoiceCaptureController.driveFrames(
                buf -> { running.set(false); return AudioReadCodes.DEAD_OBJECT; },
                (pcm, n) -> dispatched.add(n), running::get);
        assertNull(reason);
        assertTrue(dispatched.isEmpty());
    }

    @Test
    public void stopAfterPositiveFrame_frameNotDispatched() {
        // P2 核心:read() 返回正 PCM 帧后、分发前 stop() 恰好发生 → 该帧不送入 KWS/ASR
        AtomicBoolean running = new AtomicBoolean(true);
        List<Integer> dispatched = new ArrayList<>();
        String reason = VoiceCaptureController.driveFrames(
                buf -> { running.set(false); return 1600; }, // read 完成时 stop() 并发到达
                (pcm, n) -> dispatched.add(n), running::get);
        assertNull("stop 后正帧应按 STOPPED 收尾", reason);
        assertTrue("stop 后的正帧不应分发", dispatched.isEmpty());
    }

    @Test
    public void dispatchError_duringStop_countedAsStopped() {
        // P2 核心:停止中端口(stop 已并发执行)抛异常 → STOPPED,不误报 CAPTURE_PIPELINE_ERROR
        AtomicBoolean running = new AtomicBoolean(true);
        String reason = VoiceCaptureController.driveFrames(
                buf -> 1600,
                (pcm, n) -> { running.set(false); throw new RuntimeException("port closed"); },
                running::get);
        assertNull("停止中的端口异常应按 STOPPED,不计 pipeline error", reason);
    }

    @Test
    public void genuinePipelineError_whileRunning_reported() {
        // 未停止时分发抛异常 → CAPTURE_PIPELINE_ERROR(不可恢复,经 onTerminated 上送)
        String reason = VoiceCaptureController.driveFrames(
                buf -> 1600,
                (pcm, n) -> { throw new RuntimeException("native crash"); },
                () -> true);
        assertEquals("CAPTURE_PIPELINE_ERROR", reason);
    }

    @Test
    public void genuineReadError_whileRunning_reportedWithCode() {
        // 未停止时负错误码 → 精确错误码上送(ERROR_INVALID_OPERATION/BAD_VALUE/DEAD_OBJECT 等)
        assertEquals("CAPTURE_READ_ERROR_-3", VoiceCaptureController.classifyReadError(-3, true));
        assertEquals("CAPTURE_READ_ERROR_" + AudioReadCodes.DEAD_OBJECT,
                VoiceCaptureController.classifyReadError(AudioReadCodes.DEAD_OBJECT, true));
        // 循环级:dispatcher 不被调用
        List<Integer> dispatched = new ArrayList<>();
        String reason = VoiceCaptureController.driveFrames(
                buf -> AudioReadCodes.DEAD_OBJECT,
                (pcm, n) -> dispatched.add(n), () -> true);
        assertEquals("CAPTURE_READ_ERROR_" + AudioReadCodes.DEAD_OBJECT, reason);
        assertTrue(dispatched.isEmpty());
    }

    @Test
    public void permissionRevoked_whileRunning_reported_afterStop_dropped() {
        // 未停止时权限撤销 → CAPTURE_PERMISSION_REVOKED
        String reason = VoiceCaptureController.driveFrames(
                buf -> { throw new SecurityException("RECORD_AUDIO revoked"); },
                (pcm, n) -> { }, () -> true);
        assertEquals("CAPTURE_PERMISSION_REVOKED", reason);
        // stop() 并发打断伴随的 SecurityException → STOPPED
        AtomicBoolean running = new AtomicBoolean(true);
        FrameReader stopThenThrow = buf -> { running.set(false); throw new SecurityException("revoked"); };
        assertNull(VoiceCaptureController.driveFrames(stopThenThrow, (pcm, n) -> { }, running::get));
    }

    @Test
    public void readThrowsRuntime_whileRunning_reportedAsReadFailed() {
        // P3: read() 抛非 SecurityException 的 RuntimeException(底层音频实现异常)→
        // CAPTURE_READ_FAILED,不可吞成 STOPPED(否则错误统计和自动恢复路径失真)
        String reason = VoiceCaptureController.driveFrames(
                buf -> { throw new IllegalStateException("audio HAL died"); },
                (pcm, n) -> { }, () -> true);
        assertEquals("CAPTURE_READ_FAILED", reason);
    }

    @Test
    public void readThrowsRuntime_duringStop_stillStopped() {
        // P3: 停止竞态下 read() 抛 RuntimeException → 仍归 STOPPED(不计故障预算)
        AtomicBoolean running = new AtomicBoolean(true);
        String reason = VoiceCaptureController.driveFrames(
                buf -> { running.set(false); throw new IllegalStateException("audio HAL died"); },
                (pcm, n) -> { }, running::get);
        assertNull(reason);
    }

    @Test
    public void zeroRead_skipped_thenPositiveFrame_dispatched() {
        // 空读 SKIP 继续;后续正帧正常分发(不在停止窗口);两帧后手动停止 → STOPPED
        AtomicInteger reads = new AtomicInteger();
        List<Integer> dispatched = new ArrayList<>();
        FrameReader reader = buf -> reads.incrementAndGet() == 1 ? 0 : 1600;
        FrameDispatcher count = (pcm, n) -> dispatched.add(n);
        String reason = VoiceCaptureController.driveFrames(reader, count, () -> dispatched.isEmpty());
        assertNull(reason);
        assertEquals(List.of(1600), dispatched);
    }

    /** android.media.AudioRecord 常见负错误码常量(JVM 测试不依赖 android.jar)。 */
    private static final class AudioReadCodes {
        static final int DEAD_OBJECT = -38; // ERROR_DEAD_OBJECT
    }
}
