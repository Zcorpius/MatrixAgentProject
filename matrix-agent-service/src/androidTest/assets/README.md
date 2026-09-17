# androidTest assets

## hey-matrix-16k-mono.pcm

正向唤醒门禁样本(`voicecert/VoskRecognizeTest.englishWake_positiveSample_firesWithStartEpoch`)。
格式:16 kHz / 单声道 / 16-bit LE raw PCM,约 2.8s。

**来源(可追溯)**:macOS 内置 TTS(`say`,Samantha 语音)合成 "hey matrix",
两段(常速 + `-r 140` 慢速)以 400ms 静音间隔拼接,前后各垫 300ms 静音。
生成日期:2026-08-16,生成机器:darwin 25.4.0(macOS)。

再生成命令(在 macOS 任意目录执行,产物剥 WAV 头拼 raw):

```sh
say -v Samantha -o samantha.wav --data-format=LEI16@16000 "hey matrix"
say -v Samantha -r 140 -o samantha_slow.wav --data-format=LEI16@16000 "hey matrix"
python3 - <<'PY'
import wave
frames = lambda p: wave.open(p).readframes(wave.open(p).getnframes())
sil = lambda ms: b'\x00' * int(16000 * 2 * ms / 1000)
open('hey-matrix-16k-mono.pcm', 'wb').write(
    sil(300) + frames('samantha.wav') + sil(400) + frames('samantha_slow.wav') + sil(300))
PY
```

替换为真人声样本时保持同名同格式即可(真人声对 KWS 更友好)。
