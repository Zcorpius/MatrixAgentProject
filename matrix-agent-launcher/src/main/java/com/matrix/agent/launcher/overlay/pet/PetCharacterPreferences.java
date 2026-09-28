package com.matrix.agent.launcher.overlay.pet;

import android.content.Context;

/** User-selected floating character, stored independently from color and light/dark appearance. */
public final class PetCharacterPreferences {
    private static final String PREFS = "launcher_appearance";
    private static final String KEY_CHARACTER = "floating_pet_character";

    private PetCharacterPreferences() {}

    public static PetCharacter get(Context context) {
        String key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CHARACTER, PetCharacter.YUKINO.assetDirectory());
        return PetCharacter.fromKey(key);
    }

    public static void set(Context context, PetCharacter character) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CHARACTER, character.assetDirectory()).apply();
    }
}
