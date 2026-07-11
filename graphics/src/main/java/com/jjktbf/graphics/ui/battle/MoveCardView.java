package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;

/** A pixel-art move card in the planner's bottom palette. */
public class MoveCardView {

    public static final float CARD_W = 176f;
    public static final float CARD_H = 112f;

    private final Move move;
    private final Rectangle bounds;
    private boolean disabled;
    private boolean hovered;
    private boolean dragging;

    public MoveCardView(Move move, float x, float y) {
        this.move = move;
        this.bounds = new Rectangle(x, y, CARD_W, CARD_H);
    }

    public Move getMove()                    { return move; }
    public Rectangle getBounds()             { return bounds; }
    public boolean isDisabled()              { return disabled; }
    public void setDisabled(boolean value)   { disabled = value; }
    public void setHovered(boolean value)    { hovered = value; }
    public void setDragging(boolean value)   { dragging = value; }

    public static Color typeColorFor(MoveCategory category) {
        if (category == null) return Color.GRAY;
        return switch (category) {
            case PHYSICAL -> new Color(0.850f, 0.380f, 0.190f, 1f);
            case INNATE_TECHNIQUE -> new Color(0.560f, 0.280f, 0.820f, 1f);
            case NON_INNATE_TECHNIQUE -> new Color(0.220f, 0.530f, 0.900f, 1f);
            case PHYSICAL_CURSED_ENERGY,
                PHYSICAL_INNATE_TECHNIQUE,
                PHYSICAL_NON_INNATE_TECHNIQUE,
                INNATE_NON_INNATE_TECHNIQUE,
                PHYSICAL_INNATE_NON_INNATE_TECHNIQUE -> new Color(0.130f, 0.690f, 0.570f, 1f);
            case UTILITY -> new Color(0.450f, 0.510f, 0.610f, 1f);
            case DEFENSIVE -> new Color(0.940f, 0.690f, 0.140f, 1f);
        };
    }

    public void draw(Batch batch, BitmapFont font, BattleUiAssets ui, int actualCeCost) {
        float x = bounds.x;
        float y = bounds.y;
        float w = bounds.width;
        float h = bounds.height;
        if (disabled) {
            ui.cardDisabled.draw(batch, x, y, w, h);
        } else if (hovered || dragging) {
            ui.cardOver.draw(batch, x, y, w, h);
        } else {
            ui.card.draw(batch, x, y, w, h);
        }

        Color type = typeColorFor(move.getCategory());
        if (disabled) type = new Color(type).lerp(Color.GRAY, 0.65f);
        batch.setColor(type);
        batch.draw(ui.pixel, x + 10f, y + 12f, 9f, h - 24f);
        batch.setColor(Color.WHITE);

        Color ink = disabled ? BattleUiAssets.MUTED : BattleUiAssets.TEXT;
        font.setColor(ink);
        font.draw(batch, label(move.getName(), 23), x + 30f, y + h - 16f);
        font.setColor(disabled ? BattleUiAssets.MUTED : type);
        font.draw(batch, typeName(move.getCategory()), x + 30f, y + h - 31f);

        font.setColor(ink);
        String description = label(move.getDescription(), 34);
        font.draw(batch, description, x + 30f, y + h - 50f);
        font.draw(batch, "AP " + move.getApCost() + "   CE " + actualCeCost, x + 30f, y + 26f);
        font.draw(batch, detailLabel(), x + 30f, y + 12f);
    }

    private String detailLabel() {
        if (move.isDefensive()) return "BLOCK";
        if (move.isNeverMiss()) return "HIT: SURE";
        return "ACC " + (int) Math.round(move.getBaseAccuracy() * 100d) + "%  PWR " + move.getBasePower();
    }

    private static String typeName(MoveCategory category) {
        if (category == null) return "UNKNOWN";
        return switch (category) {
            case PHYSICAL -> "PHYSICAL";
            case INNATE_TECHNIQUE -> "INNATE";
            case NON_INNATE_TECHNIQUE -> "TECHNIQUE";
            case UTILITY -> "UTILITY";
            case DEFENSIVE -> "DEFENSIVE";
            default -> "HYBRID";
        };
    }

    private static String label(String value, int maxCharacters) {
        if (value == null || value.isBlank()) return "-";
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, maxCharacters - 1) + ".";
    }
}
