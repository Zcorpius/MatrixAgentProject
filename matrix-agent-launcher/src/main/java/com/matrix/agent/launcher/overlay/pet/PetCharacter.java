package com.matrix.agent.launcher.overlay.pet;

import com.matrix.agent.launcher.R;

/** A bundled, validated character whose animation contract is shared by every pet view. */
public enum PetCharacter {
    YUKINO("yukino", "雪之下雪乃", null, R.string.pet_character_yukino),
    REI("rei-ayanami", "绫波丽", "rei-ayanami", R.string.pet_character_rei),
    NING_YAO("ning-yao", "宁姚", "ning-yao", R.string.pet_character_ning_yao),
    WHALE("deepseek-whale-chan", "DeepSeek鲸鱼娘", "deepseek-whale-chan",
            R.string.pet_character_whale);

    private final String assetDirectory;
    private final String displayName;
    private final String petId;
    private final int label;

    PetCharacter(String assetDirectory, String displayName, String petId, int label) {
        this.assetDirectory = assetDirectory;
        this.displayName = displayName;
        this.petId = petId;
        this.label = label;
    }

    public String assetDirectory() { return assetDirectory; }
    public String displayName() { return displayName; }
    public String petId() { return petId; }
    public int label() { return label; }

    public static PetCharacter fromKey(String key) {
        for (PetCharacter character : values()) {
            if (character.assetDirectory.equals(key)) return character;
        }
        return YUKINO;
    }
}
