package com.matrix.agent.launcher.overlay.pet;

import android.graphics.Bitmap;

import androidx.annotation.MainThread;

import java.util.Objects;
import java.util.function.Consumer;

/** One selected idle frame, shared with the floating pet's sprite repository. */
@MainThread
public final class PetPortraitSelection implements AutoCloseable {
    private final PetSpriteRepository sprites;
    private final Consumer<Bitmap> listener;
    private PetSpriteRepository.Subscription pending;
    private PetCharacter character;
    private Bitmap portrait;
    private boolean closed;

    public PetPortraitSelection(PetSpriteRepository sprites, Consumer<Bitmap> listener) {
        this.sprites = Objects.requireNonNull(sprites);
        this.listener = Objects.requireNonNull(listener);
    }

    public Bitmap portrait() { return portrait; }

    public void select(PetCharacter selected) {
        if (closed) return;
        Objects.requireNonNull(selected);
        if (character == selected && (portrait != null || pending != null)) return;
        if (pending != null) pending.close();
        character = selected;
        portrait = null;
        listener.accept(null);
        pending = sprites.loadPreview(selected, bitmap -> {
            if (closed || character != selected) return;
            pending = null;
            portrait = bitmap;
            listener.accept(bitmap);
        });
    }

    @Override public void close() {
        closed = true;
        if (pending != null) pending.close();
        pending = null;
        portrait = null;
    }
}
