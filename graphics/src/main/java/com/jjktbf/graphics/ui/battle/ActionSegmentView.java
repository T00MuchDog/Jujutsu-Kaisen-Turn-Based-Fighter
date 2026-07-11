package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.move.Move;

import java.util.ArrayList;
import java.util.List;

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

        float railW = Math.min(10f, Math.max(4f, w * 0.20f));
        batch.setColor(typeColor());
        batch.draw(ui.pixel, x + 5f, y + 5f, railW, Math.max(1f, h - 10f));
        batch.setColor(Color.WHITE);

        if (w < 18f) return;
        float labelX = x + railW + 9f;
        float labelW = w - (labelX - x) - 4f;
        font.setColor(BattleUiAssets.TEXT);
        drawMoveName(batch, font, move.getName(), labelX, y, labelW, h);
    }

    private static void drawMoveName(Batch batch, BitmapFont font, String value, float x, float y,
                                     float width, float height) {
        String name = value == null || value.isBlank() ? "MOVE" : value;
        float originalScaleX = font.getData().scaleX;
        float originalScaleY = font.getData().scaleY;
        List<String> lines = List.of(name);
        for (float scale = 1f; scale >= 0.30f; scale -= 0.10f) {
            font.getData().setScale(scale);
            lines = wrap(font, name, width);
            if (lines.size() <= 2) break;
        }

        if (lines.size() > 2) {
            lines = lines.subList(0, 2);
            lines.set(1, "...");
        }

        float lineHeight = font.getLineHeight();
        float firstY = y + height / 2f + (lines.size() - 1) * lineHeight / 2f;
        for (int i = 0; i < lines.size(); i++) {
            font.draw(batch, lines.get(i), x, firstY - i * lineHeight);
        }
        font.getData().setScale(originalScaleX, originalScaleY);
    }

    private static List<String> wrap(BitmapFont font, String value, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : value.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (textWidth(font, candidate) <= width) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static float textWidth(BitmapFont font, String value) {
        return new GlyphLayout(font, value).width;
    }
}
