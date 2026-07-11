package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.move.Move;

/**
 * Visual for an action segment. A segment is intentionally spare: the coloured
 * left rail communicates move type, and the white body carries only the move
 * name so its AP width remains easy to read against the grid.
 */
public class ActionSegmentView {

    private final ActionSegment segment;
    private final Move move;
    private final Rectangle bounds;
    private boolean highlighted;

    public ActionSegmentView(ActionSegment segment, float x, float y, float width, float height) {
        this.segment = segment;
        this.move = segment.getMove();
        this.bounds = new Rectangle(x, y, width, height);
    }

    /** Creates a visual-only segment for the drag avatar. */
    public ActionSegmentView(Move move, float x, float y, float width, float height) {
        this.segment = null;
        this.move = move;
        this.bounds = new Rectangle(x, y, width, height);
    }

    public ActionSegment getSegment()         { return segment; }
    public Move getMove()                     { return move; }
    public Rectangle getBounds()              { return bounds; }
    public void setHighlighted(boolean value) { highlighted = value; }
    public void setPosition(float x, float y) { bounds.x = x; bounds.y = y; }
    public void setWidth(float w)             { bounds.width = w; }

    public Color typeColor() {
        return MoveCardView.typeColorFor(move.getCategory());
    }

    public void draw(Batch batch, BitmapFont font, BattleUiAssets ui) {
        float x = bounds.x;
        float y = bounds.y;
        float w = bounds.width;
        float h = bounds.height;
        ui.segment(highlighted).draw(batch, x, y, w, h);

        float railW = Math.min(15f, Math.max(5f, w - 8f));
        batch.setColor(typeColor());
        batch.draw(ui.pixel, x + 5f, y + 5f, railW, Math.max(1f, h - 10f));
        batch.setColor(Color.WHITE);

        if (w < 34f) return;
        float labelX = x + railW + 10f;
        float labelW = w - (labelX - x) - 5f;
        font.setColor(BattleUiAssets.TEXT);
        font.draw(batch, abbreviated(move.getName(), labelW), labelX,
            y + h / 2f + font.getCapHeight() / 2f);
    }

    private static String abbreviated(String value, float width) {
        if (value == null || value.isBlank()) return "MOVE";
        int maxChars = Math.max(2, (int) (width / 5.5f));
        if (value.length() <= maxChars) return value;
        return maxChars < 4 ? value.substring(0, maxChars) : value.substring(0, maxChars - 1) + ".";
    }
}
