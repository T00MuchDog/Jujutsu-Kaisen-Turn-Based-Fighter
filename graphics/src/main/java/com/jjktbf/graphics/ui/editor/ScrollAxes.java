package com.jjktbf.graphics.ui.editor;

/**
 * Resolves a two-axis scroll delta down to its dominant axis.
 *
 * <p>Precision trackpads (notably on macOS) report both an X and a Y component for
 * every two-finger gesture, so an intended horizontal swipe also carries a small
 * vertical component and vice-versa. Left unchecked, that minor component leaks
 * into the orthogonal pane (a horizontal swipe nudges a vertical scroller behind
 * it). Snapping each gesture to its dominant axis makes a swipe move content only
 * along the direction the finger actually travelled.
 */
public final class ScrollAxes {

    /** When both axes are nonzero, the larger one must beat the other by this factor. */
    private static final float DOMINANCE_RATIO = 1.5f;

    private ScrollAxes() { }

    /**
     * Zeroes the minor axis of a scroll delta so the result lies purely on the
     * dominant axis, or is returned unchanged when the gesture is already axial.
     *
     * @param amountX raw horizontal scroll amount
     * @param amountY raw vertical scroll amount
     * @return {@code [amountX, amountY]} with the minor axis set to zero
     */
    public static float[] dominant(float amountX, float amountY) {
        if (amountX == 0f || amountY == 0f) return new float[] {amountX, amountY};
        float absX = Math.abs(amountX);
        float absY = Math.abs(amountY);
        if (absX >= absY * DOMINANCE_RATIO) return new float[] {amountX, 0f};
        if (absY >= absX * DOMINANCE_RATIO) return new float[] {0f, amountY};
        // Gesture is ambiguous (near-diagonal): let both axes through unchanged so
        // an oblique swipe still feels responsive rather than dead.
        return new float[] {amountX, amountY};
    }
}
