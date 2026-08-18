package com.jjktbf.graphics.ui.profile;

import java.util.Locale;

/** Stable UI presentation profiles. Gameplay and battle state remain shared. */
public enum UiProfile {
    MAC("mac", 1512, 982, 1.0f),
    WINDOWS("windows", 2560, 1440, 1.5f);

    private final String fileStem;
    private final int defaultReferenceWidth;
    private final int defaultReferenceHeight;
    private final float textScale;

    UiProfile(String fileStem, int defaultReferenceWidth, int defaultReferenceHeight,
              float textScale) {
        this.fileStem = fileStem;
        this.defaultReferenceWidth = defaultReferenceWidth;
        this.defaultReferenceHeight = defaultReferenceHeight;
        this.textScale = textScale;
    }

    public String fileStem() {
        return fileStem;
    }

    public int defaultReferenceWidth() {
        return defaultReferenceWidth;
    }

    public int defaultReferenceHeight() {
        return defaultReferenceHeight;
    }

    public float textScale() {
        return textScale;
    }

    public static UiProfile parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("UI profile must be MAC or WINDOWS");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                "Unknown UI profile '" + value + "'; expected MAC or WINDOWS", invalid);
        }
    }
}
