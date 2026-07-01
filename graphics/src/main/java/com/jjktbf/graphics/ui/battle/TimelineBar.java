package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.combat.Timeline;

import java.util.List;

/**
 * One of the two horizontal timeline bars (offensive or defensive).
 *
 * <p>A black rounded rectangle with a light-grey border, containing
 * {@value Timeline#DEFAULT_GRID_LENGTH} equally-spaced grey dots representing
 * the AP points. The offensive bar emits a soft orange glow and ends with a
 * sword glyph; the defensive bar emits a soft blue glow and ends with a shield
 * glyph.
 *
 * <p>The bar is a pure spatial board: it maps dot index → pixel x and hosts
 * placed {@link ActionSegmentView}s. Budget/board-assignment logic lives on
 * {@link com.jjktbf.model.combat.BattlePlan}.
 */
public class TimelineBar {

    public enum Kind { OFFENSIVE, DEFENSIVE }

    private static final Color ORANGE_GLOW = new Color(1.0f, 0.55f, 0.20f, 0.35f);
    private static final Color BLUE_GLOW   = new Color(0.35f, 0.55f, 1.00f, 0.35f);

    private final Kind kind;
    private final Rectangle bounds;
    private final int dotCount;       // 300
    private final float dotSpacing;   // px between consecutive dot centres

    public TimelineBar(Kind kind, float x, float y, float width, float height) {
        this.kind = kind;
        this.bounds = new Rectangle(x, y, width, height);
        this.dotCount = Timeline.DEFAULT_GRID_LENGTH;
        // Dots span the inner width; segment width math uses half-spacing edges.
        this.dotSpacing = width / dotCount;
    }

    public Kind getKind()        { return kind; }
    public Rectangle getBounds() { return bounds; }
    public float getDotSpacing() { return dotSpacing; }
    public int getDotCount()     { return dotCount; }

    /** Pixel x (centre) of dot {@code tick} (1-indexed). */
    public float dotX(int tick) {
        // dot 1 centre at left inner edge + half spacing
        return bounds.x + (tick - 0.5f) * dotSpacing;
    }

    /**
     * The pixel range a segment of {@code apCost} dots occupies when anchored at
     * {@code startTick}: from half a spacing before the first dot to half a
     * spacing after the last dot (so a full bar has no empty edge space).
     */
    public float segmentLeft(int startTick)  { return dotX(startTick) - dotSpacing / 2f; }
    public float segmentWidth(int apCost)    { return apCost * dotSpacing; }

    /** Nearest dot tick for a pixel x (1-indexed), clamped to [1, dotCount]. */
    public int tickAtX(float x) {
        int t = Math.round((x - bounds.x) / dotSpacing + 0.5f);
        return Math.max(1, Math.min(dotCount, t));
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    public void drawShapes(ShapeRenderer sr) {
        float x = bounds.x, y = bounds.y, w = bounds.width, h = bounds.height;

        // Soft glow
        sr.set(ShapeType.Filled);
        sr.setColor(kind == Kind.OFFENSIVE ? ORANGE_GLOW : BLUE_GLOW);
        MoveCardView.roundRect(sr, x - 5f, y - 5f, w + 10f, h + 10f, 10f);

        // Body: black rounded rect
        sr.setColor(new Color(0.07f, 0.07f, 0.09f, 1f));
        MoveCardView.roundRect(sr, x, y, w, h, 8f);

        // Light-grey border
        sr.set(ShapeType.Line);
        sr.setColor(new Color(0.75f, 0.75f, 0.78f, 1f));
        MoveCardView.roundRect(sr, x, y, w, h, 8f);

        // Dots — drawn small enough to read as a fine grid. For 300 dots on a
        // ~600px bar, each is ~2px; we draw every Nth to avoid overdraw noise
        // while keeping the "dotted grid" feel.
        sr.set(ShapeType.Filled);
        sr.setColor(new Color(0.42f, 0.42f, 0.46f, 1f));
        int step = Math.max(1, dotCount / 150); // cap to ~150 visible dots
        float dotR = Math.min(1.5f, dotSpacing / 4f);
        float midY = y + h / 2f;
        for (int i = 1; i <= dotCount; i += step) {
            sr.circle(dotX(i), midY, dotR);
        }
    }

    public void drawSymbols(Batch batch, BitmapFont font) {
        // End glyph (sword ⚔ / shield 🛡) just past the right edge.
        String glyph = kind == Kind.OFFENSIVE ? "⚔" : "🛡";
        float gx = bounds.x + bounds.width + 10f;
        float gy = bounds.y + bounds.height / 2f + 6f;
        font.setColor(kind == Kind.OFFENSIVE ? new Color(1f, 0.6f, 0.2f, 1f)
                                             : new Color(0.4f, 0.6f, 1f, 1f));
        font.draw(batch, glyph, gx, gy);
    }

    /** Layout helper: place each segment view at its dot-anchored pixel range. */
    public void layoutSegments(List<ActionSegmentView> views) {
        for (ActionSegmentView v : views) {
            int start = v.getSegment().getStartTick();
            int ap = v.getSegment().getMove().getApCost();
            v.setPosition(segmentLeft(start), bounds.y + 4f);
            v.setWidth(segmentWidth(ap));
        }
    }
}
