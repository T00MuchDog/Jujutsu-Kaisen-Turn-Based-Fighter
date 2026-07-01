package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;

/**
 * A move card in the bottom palette: a white tall rectangle showing the move's
 * identity and stats. Draggable to produce an {@link ActionSegmentView} on a
 * timeline bar.
 *
 * <p>Layout (per the planning-UI spec):
 * <pre>
 *   ┌──────────────────────────────────┐
 *   │ [type]  NAME              CE:NN  │
 *   │ description (e.g. "A basic ...")  │
 *   │ ACC%       AP:NN       PWR:NN    │
 *   └──────────────────────────────────┘
 * </pre>
 * — type chip top-left, name top-middle (bold), CE cost top-right,
 *   description middle, accuracy bottom-left, AP cost bottom-middle,
 *   base power bottom-right.
 *
 * <p>This first draft draws only — drag handling is added by {@code PlanningPanel}.
 * When {@code disabled} (CE/AP unaffordable) the card is greyed and dimmed.
 */
public class MoveCardView {

    public static final float CARD_W = 150f;
    public static final float CARD_H = 96f;

    private final Move move;
    private final Rectangle bounds;
    private boolean disabled;

    public MoveCardView(Move move, float x, float y) {
        this.move = move;
        this.bounds = new Rectangle(x, y, CARD_W, CARD_H);
    }

    public Move getMove()             { return move; }
    public Rectangle getBounds()      { return bounds; }
    public float getX()               { return bounds.x; }
    public float getY()               { return bounds.y; }
    public boolean isDisabled()       { return disabled; }
    public void setDisabled(boolean d){ this.disabled = d; }

    /** Colour for the type chip, keyed off the move's category. */
    public Color typeColor() {
        return typeColorFor(move.getCategory());
    }

    /** Static so the drag avatar (ActionSegmentView) can share the mapping. */
    public static Color typeColorFor(MoveCategory cat) {
        if (cat == null) return Color.GRAY;
        return switch (cat) {
            case PHYSICAL                       -> new Color(0.85f, 0.55f, 0.30f, 1f); // terracotta
            case INNATE_TECHNIQUE               -> new Color(0.55f, 0.30f, 0.80f, 1f); // violet
            case NON_INNATE_TECHNIQUE           -> new Color(0.30f, 0.55f, 0.80f, 1f); // blue
            case PHYSICAL_CURSED_ENERGY,
                 PHYSICAL_INNATE_TECHNIQUE,
                 PHYSICAL_NON_INNATE_TECHNIQUE,
                 INNATE_NON_INNATE_TECHNIQUE,
                 PHYSICAL_INNATE_NON_INNATE_TECHNIQUE -> new Color(0.30f, 0.75f, 0.55f, 1f); // teal hybrid
            case UTILITY                        -> new Color(0.65f, 0.65f, 0.70f, 1f); // grey
            case DEFENSIVE                      -> new Color(0.95f, 0.80f, 0.30f, 1f); // gold
        };
    }

    /** Short label for the chip (e.g. "PHYS", "INNATE"). */
    public static String typeLabel(MoveCategory cat) {
        if (cat == null) return "?";
        String n = cat.name();
        // First word, capped at 6 chars.
        int space = n.indexOf('_');
        String first = space > 0 ? n.substring(0, space) : n;
        return first.length() <= 6 ? first : first.substring(0, 6);
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    public void drawShapes(ShapeRenderer sr) {
        float x = bounds.x, y = bounds.y, w = bounds.width, h = bounds.height;

        // Card body: white rounded rect (greyed when disabled).
        sr.setColor(disabled ? new Color(0.75f, 0.75f, 0.78f, 1f) : Color.WHITE);
        sr.set(ShapeType.Filled);
        roundRect(sr, x, y, w, h, 6f);

        // Border
        sr.setColor(disabled ? new Color(0.6f, 0.6f, 0.63f, 1f)
                             : new Color(0.2f, 0.2f, 0.25f, 1f));
        sr.set(ShapeType.Line);
        roundRect(sr, x, y, w, h, 6f);

        // Type chip (top-left)
        float chipW = 34f, chipH = 16f;
        Color chip = disabled ? typeColor().lerp(Color.GRAY, 0.5f) : typeColor();
        sr.set(ShapeType.Filled);
        sr.setColor(chip);
        roundRect(sr, x + 6f, y + h - chipH - 6f, chipW, chipH, 3f);
    }

    public void drawText(Batch batch, BitmapFont font) {
        float x = bounds.x, y = bounds.y, w = bounds.width, h = bounds.height;
        Color ink = disabled ? new Color(0.55f, 0.55f, 0.58f, 1f)
                             : new Color(0.1f, 0.1f, 0.15f, 1f);
        font.setColor(ink);

        // Name (top-middle, bold-ish via uppercase for the pixel font)
        String name = move.getName();
        font.draw(batch, name, x + 44f, y + h - 14f, w - 80f, 1, true);

        // CE cost (top-right)
        font.draw(batch, "CE:" + move.getBaseCeCost(), x + w - 40f, y + h - 14f);

        // Description (middle) — clamp length
        String desc = move.getDescription() == null || move.getDescription().isBlank()
            ? "" : move.getDescription();
        if (desc.length() > 22) desc = desc.substring(0, 21) + "…";
        font.draw(batch, desc, x + 8f, y + h - 44f);

        // Bottom row: ACC (left), AP (middle), PWR (right)
        int acc = (int) Math.round(move.getBaseAccuracy() * 100.0);
        font.draw(batch, "ACC " + acc + "%", x + 8f, y + 14f);
        font.draw(batch, "AP " + move.getApCost(), x + w / 2f - 14f, y + 14f);
        font.draw(batch, "PWR " + move.getBasePower(), x + w - 50f, y + 14f);
    }

    /** Draw a filled rounded rectangle. */
    public static void roundRect(ShapeRenderer sr, float x, float y, float w, float h, float r) {
        sr.rect(x + r, y, w - 2 * r, h);
        sr.rect(x, y + r, w, h - 2 * r);
        sr.arc(x + r,     y + r,     r, 180f, 90f);
        sr.arc(x + w - r, y + r,     r, 270f, 90f);
        sr.arc(x + w - r, y + h - r, r,   0f, 90f);
        sr.arc(x + r,     y + h - r, r,  90f, 90f);
    }
}
