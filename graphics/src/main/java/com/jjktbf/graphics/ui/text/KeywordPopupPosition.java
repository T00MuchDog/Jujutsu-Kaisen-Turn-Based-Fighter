package com.jjktbf.graphics.ui.text;

/** Places keyword popups beside their source text without leaving the viewport. */
public final class KeywordPopupPosition {

    private KeywordPopupPosition() { }

    public static Position place(
        float wordX,
        float wordY,
        float wordWidth,
        float wordHeight,
        float popupWidth,
        float popupHeight,
        float viewportWidth,
        float viewportHeight
    ) {
        float wordRight = wordX + wordWidth;
        float wordTop = wordY + wordHeight;

        // Preferred: the popup's bottom-left corner touches the word's top-right.
        float x = wordRight;
        float y = wordTop;

        // If that leaves the viewport, place it left and/or below the word instead.
        if (x + popupWidth > viewportWidth) x = wordX - popupWidth;
        if (y + popupHeight > viewportHeight) y = wordY - popupHeight;

        x = clamp(x, 0f, Math.max(0f, viewportWidth - popupWidth));
        y = clamp(y, 0f, Math.max(0f, viewportHeight - popupHeight));
        return new Position(x, y);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Position(float x, float y) { }
}
