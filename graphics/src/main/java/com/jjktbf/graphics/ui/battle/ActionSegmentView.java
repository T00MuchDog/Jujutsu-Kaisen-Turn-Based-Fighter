package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.move.Move;

/**
 * A placed action segment on a {@link TimelineBar}: a white rounded rectangle
 * whose width covers exactly its AP-cost dots (plus half a dot's gap each side,
 * handled by the bar's dot spacing). Shows just the type chip + name header
 * (the "top part of the move cut off" per the planning-UI spec). Height equals
 * one timeline's height.
 *
 * <p>Drag-to-move + snap-to-grid behaviour is added by {@code PlanningPanel};
 * this first draft draws only.
 */
public class ActionSegmentView {

    private final ActionSegment segment;
    /** Pixel rect on the bar. x/y/width set by the bar layout; height constant. */
    private final Rectangle bounds;
    private boolean glow;   // yellow snap-near glow

    public ActionSegmentView(ActionSegment segment, float x, float y, float width, float height) {
        this.segment = segment;
        this.bounds = new Rectangle(x, y, width, height);
    }

    public ActionSegment getSegment()         { return segment; }
    public Rectangle getBounds()              { return bounds; }
    public void setGlow(boolean glow)         { this.glow = glow; }
    public void setPosition(float x, float y) { bounds.x = x; bounds.y = y; }
    public void setWidth(float w)             { bounds.width = w; }

    public Color typeColor() {
        return MoveCardView.typeColorFor(segment.getMove().getCategory());
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    public void drawShapes(ShapeRenderer sr) {
        float x = bounds.x, y = bounds.y, w = bounds.width, h = bounds.height;

        // Snap-near glow (yellow, drawn behind/around)
        if (glow) {
            sr.set(ShapeType.Filled);
            sr.setColor(new Color(1f, 0.89f, 0.18f, 0.5f));
            MoveCardView.roundRect(sr, x - 3f, y - 3f, w + 6f, h + 6f, 8f);
        }

        // Body: white rounded rect
        sr.set(ShapeType.Filled);
        sr.setColor(Color.WHITE);
        MoveCardView.roundRect(sr, x, y, w, h, 5f);

        // Border
        sr.set(ShapeType.Line);
        sr.setColor(new Color(0.2f, 0.2f, 0.25f, 1f));
        MoveCardView.roundRect(sr, x, y, w, h, 5f);

        // Type chip (top-left)
        float chipW = 24f, chipH = 12f;
        sr.set(ShapeType.Filled);
        sr.setColor(typeColor());
        MoveCardView.roundRect(sr, x + 4f, y + h - chipH - 4f, chipW, chipH, 3f);
    }

    public void drawText(Batch batch, BitmapFont font) {
        Move move = segment.getMove();
        float x = bounds.x, y = bounds.y, w = bounds.width, h = bounds.height;
        font.setColor(new Color(0.1f, 0.1f, 0.15f, 1f));
        // Name to the right of the chip
        String name = move.getName();
        font.draw(batch, name, x + 32f, y + h - 8f, w - 36f, 1, true);
    }
}
