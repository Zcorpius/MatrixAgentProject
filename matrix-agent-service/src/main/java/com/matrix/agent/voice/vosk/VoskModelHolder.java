package com.matrix.agent.voice.vosk;

import android.util.Log;

import com.matrix.agent.voice.ModelInstallLock;
import com.matrix.agent.voice.ModelPathResolver;

import org.vosk.Model;

import java.io.File;
import java.io.IOException;

/**
 * Vosk 模型持有者(懒加载单例)。
 *
 * <p>持有英文唤醒模型(en)与中文 ASR 模型(cn)的 {@link Model} 实例。{@link Model} 加载重
 * (数百 ms ~ 秒级),按需懒加载、复用。语言目录 {@code <root>/en}、{@code <root>/cn} 下由
 * {@link ModelPathResolver} 管理 active/previous 版本指针,加载读 active 版本目录。
 *
 * <p>active 版本加载失败(Model native 构造异常)→ 回退 previous 版本目录。
 *
 * <p>线程安全:加载方法 synchronized。Model 本身 Vosk 保证线程安全可被多 Recognizer 共享。
 */
public final class VoskModelHolder {
    private static final String TAG = "MatrixAgent";

    private final File enDir;
    private final File cnDir;
    private Model enModel;
    private Model cnModel;

    public VoskModelHolder(File voskModelRoot) {
        if (voskModelRoot == null) throw new IllegalArgumentException("voskModelRoot 不能为空");
        this.enDir = new File(voskModelRoot, "en");
        this.cnDir = new File(voskModelRoot, "cn");
    }

    /** 英文唤醒模型(首次调用加载,active 失败回退 previous)。 */
    public synchronized Model enModel() throws IOException {
        if (enModel == null) enModel = loadWithFallback(enDir);
        return enModel;
    }

    /** 中文 ASR 模型(首次调用加载,active 失败回退 previous)。 */
    public synchronized Model cnModel() throws IOException {
        if (cnModel == null) cnModel = loadWithFallback(cnDir);
        return cnModel;
    }

    /**
     * 读 active 版本目录加载;active 加载失败(native 构造异常)→ previous 版本回退。
     */
    private Model loadWithFallback(File langDir) throws IOException {
        File active = ModelPathResolver.activeDir(langDir);
        if (active == null) {
            throw new IOException("无 active 模型版本: " + langDir.getName());
        }
        try {
            return new Model(active.getAbsolutePath());
        } catch (IOException e) {
            File prev = ModelPathResolver.previousDir(langDir);
            if (prev != null) {
                Log.w(TAG, "[Voice] active 加载失败,回退 previous: " + langDir.getName()
                        + " (" + e.getClass().getSimpleName() + ")");
                Model fallback = new Model(prev.getAbsolutePath());
                // previous 成功加载后回滚指针(active←previous,删坏 active),确保后续 promote
                // 不会把坏版本留作 previous 导致回滚链断裂。回滚失败不阻塞本次(下次启动重试)。
                // P1-5b: 经统一模型锁回滚(阻塞等持锁 install 完成,防与 promote 竞争指针)
                try {
                    ModelInstallLock.withLock(langDir, () -> ModelPathResolver.rollbackActive(langDir));
                } catch (IOException re) {
                    Log.w(TAG, "[Voice] active 指针回滚失败(本次仍用 previous,下次启动重试): "
                            + langDir.getName());
                }
                return fallback;
            }
            throw e;
        }
    }

    /** 是否已加载(不触发加载)。 */
    public synchronized boolean isEnLoaded() { return enModel != null; }

    public synchronized boolean isCnLoaded() { return cnModel != null; }

    /** 释放模型。进程退出或不再使用语音时调用。 */
    public synchronized void close() {
        if (enModel != null) { enModel.close(); enModel = null; }
        if (cnModel != null) { cnModel.close(); cnModel = null; }
    }
}
