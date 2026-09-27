package com.matrix.agent.host.di;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.telephony.TelephonyManager;
import com.matrix.agent.identity.ExecutionScope;
import com.matrix.agent.voice.SpeakableResponse;
import com.matrix.agent.voice.platform.AndroidAudioFocusAdapter;
import com.matrix.agent.voice.port.TtsPort;
import com.matrix.agent.voice.sherpa.VoiceTtsFactory;
import java.util.concurrent.*;

/** Per-delivery TTS lifecycle, independent of capture/ASR and constrained by saved network authority. */
public final class ScheduledSpeech {
    private final ExecutorService synthesis = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> new Thread(r, "matrix-schedule-tts"), new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore voiceSlot = new Semaphore(1);
    public void close() { synthesis.shutdownNow(); }
    public String speak(AppContainer host, String utterance, String text, ExecutionScope scope, long timeout) {
        Context context = host.getAppContext();
        if (!allowed(context) || !scope.rejection().isEmpty()) return "SUPPRESSED_BY_POLICY";
        if (!voiceSlot.tryAcquire()) return "SUPPRESSED_BY_ACTIVE_SPEECH";
        try { return speakWithLease(host, utterance, text, scope, timeout); }
        finally { voiceSlot.release(); }
    }
    private String speakWithLease(AppContainer host, String utterance, String text, ExecutionScope scope, long timeout) {
        Context context = host.getAppContext();
        var focus = new AndroidAudioFocusAdapter(context);
        var ready = new CompletableFuture<String>(); var finished = new CompletableFuture<String>();
        var port = new VoiceTtsFactory((Application) context, host.getModelDownloadDao(),
                host.getHttpClient().download(), host.getHttpClient().provider()).create(synthesis, scope.networkAllowed());
        long deadline = android.os.SystemClock.elapsedRealtime() + Math.min(timeout, 15_000);
        try {
            port.setListener(new TtsPort.Listener() {
                public void onReady() { ready.complete(""); }
                public void onInitError(String code) { ready.complete(code); }
                public void onDone(String id) { if (utterance.equals(id)) finished.complete(""); }
                public void onError(String id, String code) { if (utterance.equals(id)) finished.complete(code); }
            });
            focus.setListener(() -> { port.stop(); finished.complete("SUPPRESSED_BY_POLICY"); });
            String initialization = ready.get(Math.max(1, deadline - android.os.SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            if (!initialization.isEmpty()) return initialization;
            if (!allowed(context) || !scope.rejection().isEmpty() || !focus.request()) return "SUPPRESSED_BY_POLICY";
            String safe = new com.matrix.agent.task.redact.AuditRedactor(1000).redact(text);
            port.speak(new SpeakableResponse(safe, "zh-CN", com.matrix.agent.task.TaskState.SUCCEEDED), utterance);
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!allowed(context) || !scope.rejection().isEmpty() || scope.remainingMillis() == 0) return "SUPPRESSED_BY_POLICY";
                try { return finished.get(100, TimeUnit.MILLISECONDS); } catch (TimeoutException pending) { /* Bounded cancellation/policy check. */ }
            }
            return "TTS_TIMEOUT";
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return "TTS_CANCELLED"; }
        catch (Exception failed) { return "TTS_UNAVAILABLE"; }
        finally { port.stop(); port.shutdown(); focus.release(); }
    }
    private static boolean allowed(Context context) {
        int hour = java.time.LocalTime.now().getHour();
        if (hour >= 22 || hour < 7) return false;
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null || notifications.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) return false;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null || audio.getMode() != AudioManager.MODE_NORMAL || audio.isMusicActive()) return false;
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false;
        TelephonyManager phone = context.getSystemService(TelephonyManager.class);
        try { return phone != null && phone.getCallState() == TelephonyManager.CALL_STATE_IDLE; }
        catch (SecurityException unavailable) { return false; }
    }
}
