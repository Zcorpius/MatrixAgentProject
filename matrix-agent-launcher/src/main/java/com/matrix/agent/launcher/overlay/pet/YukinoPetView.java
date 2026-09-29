package com.matrix.agent.launcher.overlay.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;

import androidx.annotation.MainThread;

import com.matrix.agent.launcher.R;
import com.matrix.agent.launcher.presentation.theme.LauncherThemePreferences;
import com.matrix.agent.launcher.overlay.pet.PetPresentation.Indicator;
import com.matrix.agent.launcher.overlay.pet.PetPresentation.Motion;
import com.matrix.agent.launcher.overlay.pet.PetPresentation.Repeat;

import java.util.Objects;

/** Main-thread sprite player. Owns callbacks, but never the shared bitmap lifetime or window gestures. */
@MainThread
@android.annotation.SuppressLint("ViewConstructor") // Programmatic window-only view with an explicit repository owner.
public class YukinoPetView extends View implements AutoCloseable {
    private final PetSpriteRepository sprites;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::advance;
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destination = new RectF();
    private final int successColor, errorColor, warningColor, mutedColor, outlineColor;
    private PetCharacter character;
    private PetSpriteRepository.Subscription loading, posterLoading;
    private PetSpriteRepository.Clip clip;
    private Bitmap poster;
    private String roundId;
    private PetPresentation presentation = new PetPresentation(Motion.IDLE, Indicator.NONE);
    private Motion playing = Motion.IDLE;
    private enum Attention { NONE, DRAG, LOOK, WAVE }
    private Attention attention = Attention.NONE;
    private int lookDirection = -1;
    private final Runnable releaseLook = this::cancelLook;
    private record Resume(Motion motion, PetSpriteRepository.Clip clip, int frame, long elapsedMs) {}
    private Resume suspended;
    private int frame;
    private long loadGeneration, elapsedBeforeRun, runStarted;
    private boolean playbackEnabled, running, closed;

    public YukinoPetView(Context context, PetSpriteRepository sprites) {
        this(context, sprites, PetCharacterPreferences.get(context));
    }

    public YukinoPetView(Context context, PetSpriteRepository sprites, PetCharacter character) {
        super(context);
        this.sprites = sprites;
        this.character = character;
        successColor = LauncherThemePreferences.colorResource(context, R.color.overlay_success);
        errorColor = LauncherThemePreferences.colorResource(context, R.color.overlay_danger);
        warningColor = LauncherThemePreferences.colorResource(context, R.color.overlay_warning);
        mutedColor = LauncherThemePreferences.colorResource(context, R.color.overlay_muted);
        outlineColor = LauncherThemePreferences.colorResource(context, R.color.overlay_surface);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        var neutral = sprites.cached(character, Motion.NEUTRAL);
        if (neutral != null) poster = neutral.frames().get(0);
        else posterLoading = sprites.load(character, Motion.NEUTRAL, result -> {
            posterLoading = null;
            if (!closed && result.clip() != null) {
                poster = result.clip().frames().get(0);
                invalidate();
            }
        });
        select(Motion.IDLE);
    }

    @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }

    public void present(String roundId, PetPresentation value) {
        if (closed) return;
        boolean newMotion = !Objects.equals(this.roundId, roundId) || presentation.motion() != value.motion();
        this.roundId = roundId;
        presentation = value;
        // A completed one-shot remains completed across history, draft, and connection updates.
        if (newMotion) {
            if (attention == Attention.NONE) select(value.motion());
            else {
                suspended = new Resume(value.motion(), null, 0, 0);
                if (attention != Attention.DRAG && important(value.motion())) endAttention();
            }
        }
        invalidate();
    }

    /** Switch identity while retaining the current task motion and any temporary gesture state. */
    public void setCharacter(PetCharacter value) {
        if (closed || character == value) return;
        character = value;
        poster = null;
        if (posterLoading != null) posterLoading.close();
        posterLoading = sprites.load(character, Motion.NEUTRAL, result -> {
            posterLoading = null;
            if (!closed && character == value && result.clip() != null) {
                poster = result.clip().frames().get(0);
                invalidate();
            }
        });
        if (suspended != null) suspended = new Resume(suspended.motion(), null, 0, 0);
        select(new Resume(playing, null, 0, 0));
    }

    /** Start IO at pointer-down, before the drag threshold is reached; neither request owns this View. */
    public void prepareDrag() {
        if (closed) return;
        sprites.load(Motion.RUN_LEFT, ignored -> {});
        sprites.load(Motion.RUN_RIGHT, ignored -> {});
        sprites.load(Motion.LOOK, ignored -> {});
    }

    /** Temporary gesture presentation. Task updates keep their badge and become the next resume target. */
    public void drag(boolean movingLeft) {
        if (closed || !playbackEnabled) return;
        Motion motion = movingLeft ? Motion.RUN_LEFT : Motion.RUN_RIGHT;
        if (attention == Attention.DRAG && playing == motion) return;
        override(Attention.DRAG, motion, 0);
    }

    /** Direction frames are poses, not an animation timeline. */
    public void lookAt(float dx, float dy) {
        if (closed || !playbackEnabled || attention == Attention.DRAG || feedbackPending()) return;
        main.removeCallbacks(releaseLook);
        lookDirection = LookDirection.select(dx, dy, dp(12), attention == Attention.LOOK ? lookDirection : -1);
        Motion motion = lookDirection < 0 ? Motion.NEUTRAL : Motion.LOOK;
        int nextFrame = Math.max(0, lookDirection);
        if (attention == Attention.LOOK && playing == motion) {
            frame = nextFrame; invalidate(); return;
        }
        override(Attention.LOOK, motion, nextFrame);
    }

    public void releaseLook() {
        if (attention == Attention.LOOK) {
            main.removeCallbacks(releaseLook);
            main.postDelayed(releaseLook, 800);
        }
    }

    public void cancelLook() { if (attention == Attention.LOOK) endAttention(); }

    public void wave() {
        if (closed || !playbackEnabled || attention != Attention.NONE
                || presentation.indicator() != Indicator.NONE || feedbackPending()) return;
        override(Attention.WAVE, Motion.WAVING, 0);
    }

    private static boolean important(Motion motion) {
        return motion == Motion.SUCCEEDED || motion == Motion.FAILED || motion == Motion.REVIEW;
    }

    private boolean feedbackPending() {
        return attention == Attention.NONE && important(playing)
                && (clip == null || elapsed() < clip.timeline().durationMs());
    }

    private void override(Attention next, Motion motion, int pose) {
        main.removeCallbacks(releaseLook);
        if (attention == Attention.NONE) {
            pause();
            suspended = new Resume(playing, clip, frame, elapsedBeforeRun);
        }
        attention = next;
        select(new Resume(motion, null, pose, 0));
    }

    public void endDrag() {
        if (attention == Attention.DRAG) endAttention();
    }

    private void endAttention() {
        main.removeCallbacks(releaseLook);
        if (attention == Attention.NONE) return;
        attention = Attention.NONE;
        Resume resume = suspended;
        suspended = null;
        select(resume);
    }

    public void setPlaybackEnabled(boolean enabled) {
        playbackEnabled = enabled;
        if (!enabled) endAttention();
        reconcilePlayback();
    }

    private void select(Motion motion) {
        select(new Resume(motion, null, 0, 0));
    }

    private void select(Resume resume) {
        pause();
        if (loading != null) loading.close();
        long generation = ++loadGeneration;
        Motion motion = resume.motion();
        playing = motion;
        elapsedBeforeRun = resume.elapsedMs();
        frame = resume.frame();
        clip = resume.clip() != null ? resume.clip() : sprites.cached(character, motion);
        if (clip != null) {
            loading = null;
            reconcilePlayback();
        } else {
            loading = sprites.load(character, motion, result -> {
                if (closed || generation != loadGeneration) return;
                loading = null;
                // Loading failure keeps a neutral, clickable entry; it must not abort a handoff.
                clip = result.clip();
                if (clip == null && attention != Attention.NONE) { endAttention(); return; }
                reconcilePlayback();
                invalidate();
            });
        }
        invalidate();
    }

    private void reconcilePlayback() {
        boolean visible = !closed && playbackEnabled && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE;
        if (!visible || clip == null) { pause(); return; }
        if (attention == Attention.LOOK) { pause(); invalidate(); return; }
        if (running) return;
        runStarted = SystemClock.uptimeMillis();
        running = true;
        advance();
    }

    private long elapsed() {
        return elapsedBeforeRun + (running ? SystemClock.uptimeMillis() - runStarted : 0);
    }

    private void pause() {
        elapsedBeforeRun = elapsed();
        running = false;
        main.removeCallbacks(tick);
    }

    private void advance() {
        if (!running || clip == null || closed) return;
        var sample = clip.timeline().sample(elapsed(), playing.repeat() == Repeat.LOOP);
        frame = sample.frame();
        invalidate();
        if (sample.finished()) {
            pause();
            if (playing.repeat() == Repeat.THEN_IDLE) select(Motion.IDLE);
            else if (playing.repeat() == Repeat.RESUME) endAttention();
        } else main.postDelayed(tick, sample.nextFrameInMs());
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        reconcilePlayback();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible) reconcilePlayback();
        else { endAttention(); pause(); }
    }

    @Override protected void onDetachedFromWindow() {
        endAttention();
        pause();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap bitmap = clip == null ? poster : clip.frames().get(frame);
        if (bitmap != null) {
            float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
            float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
            destination.set((getWidth() - width) / 2, (getHeight() - height) / 2,
                    (getWidth() + width) / 2, (getHeight() + height) / 2);
            canvas.drawBitmap(bitmap, null, destination, imagePaint);
        } else {
            // A small vector fallback also covers corrupt/missing assets without a blank touch target.
            ink.setColor(mutedColor);
            ink.setTextSize(dp(24));
            ink.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("M", getWidth() / 2f, (getHeight() - ink.ascent() - ink.descent()) / 2f, ink);
        }
        drawIndicator(canvas);
    }

    private void drawIndicator(Canvas canvas) {
        Indicator indicator = presentation.indicator();
        if (indicator == Indicator.NONE) return;
        float radius = dp(8), x = getWidth() - radius - dp(3), y = radius + dp(3);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(outlineColor);
        canvas.drawCircle(x, y, radius + dp(1.5f), ink);
        ink.setColor(switch (indicator) {
            case SUCCESS -> successColor;
            case ERROR -> errorColor;
            case UNCERTAIN, OFFLINE -> warningColor;
            default -> mutedColor;
        });
        canvas.drawCircle(x, y, radius, ink);
        ink.setColor(Color.WHITE);
        ink.setStrokeWidth(dp(1.7f));
        ink.setStrokeCap(Paint.Cap.ROUND);
        switch (indicator) {
            case SUCCESS -> {
                canvas.drawLine(x - dp(3.5f), y, x - dp(1), y + dp(2.5f), ink);
                canvas.drawLine(x - dp(1), y + dp(2.5f), x + dp(3.5f), y - dp(2.5f), ink);
            }
            case CANCELLED -> canvas.drawLine(x - dp(3), y, x + dp(3), y, ink);
            default -> {
                ink.setTextAlign(Paint.Align.CENTER);
                ink.setTextSize(dp(12));
                canvas.drawText(indicator == Indicator.UNCERTAIN ? "?" : "!", x,
                        y - (ink.ascent() + ink.descent()) / 2, ink);
            }
        }
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    /** Read-only diagnostics for on-device lifecycle verification; never controls the player. */
    @androidx.annotation.VisibleForTesting
    record PlaybackSnapshot(Motion motion, int frame, boolean advancing) {}

    @androidx.annotation.VisibleForTesting
    PlaybackSnapshot playbackSnapshot() { return new PlaybackSnapshot(playing, frame, running); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        pause();
        ++loadGeneration;
        if (loading != null) loading.close();
        if (posterLoading != null) posterLoading.close();
        loading = null;
        posterLoading = null;
        clip = null;
        poster = null;
        suspended = null;
        attention = Attention.NONE;
        main.removeCallbacks(releaseLook);
    }
}
