package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.combat.Timeline;

import java.util.List;

/**
 * One of the two horizontal timeline bars (offensive or defensive).
 *
 * <p>A pixel-framed dark board containing {@value Timeline#DEFAULT_GRID_LENGTH}
 * equally-spaced AP dots. The frames and dots come from {@link BattleUiAssets},
 * keeping the board readable at the small sizes required by a 300-tick grid.
 *
 * <p>The bar is a pure spatial board: it maps dot index → pixel x and hosts
 * placed {@link ActionSegmentView}s. Budget/board-assignment logic lives on
 * {@link com.jjktbf.model.combat.BattlePlan}.
 */
public class TimelineBar {

    public enum Kind { OFFENSIVE, DEFENSIVE }

    private final Kind kind;
    private final Rectangle bounds;
    private final int dotCount;       // 300
    private float dotSpacing;         // px between consecutive dot centres

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

    public void setBounds(float x, float y, float width, float height) {
        bounds.set(x, y, width, height);
        // Bars are constructed before the screen is laid out, so their initial
        // placeholder width must never define the real AP grid spacing.
        dotSpacing = width / dotCount;
    }

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

    public void draw(Batch batch, BattleUiAssets ui, boolean isDropTarget) {
        ui.track(kind, isDropTarget).draw(batch, bounds.x, bounds.y, bounds.width, bounds.height);

        float dotSize = Math.max(2f, Math.min(4f, dotSpacing * 0.65f));
        float midY = bounds.y + bounds.height / 2f - dotSize / 2f;
        for (int i = 1; i <= dotCount; i++) {
            boolean major = i == 1 || i == dotCount || i % 25 == 0;
            if (major) {
                float majorSize = Math.max(3f, dotSize + 1f);
                batch.draw(ui.majorDot, dotX(i) - majorSize / 2f,
                    bounds.y + bounds.height / 2f - majorSize / 2f, majorSize, majorSize);
            } else {
                batch.draw(ui.dot, dotX(i) - dotSize / 2f, midY, dotSize, dotSize);
            }
        }
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
