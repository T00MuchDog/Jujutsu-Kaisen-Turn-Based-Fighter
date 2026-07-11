package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;

import java.util.ArrayList;
import java.util.List;

/** A pixel-art move card in the planner's bottom palette. */
public class MoveCardView {

    public static final float CARD_W = 240f;
    public static final float CARD_H = 224f;

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

    public void draw(Batch batch, BitmapFont font, BitmapFont statFont,
                     BattleUiAssets ui, int actualCeCost) {
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
        batch.draw(ui.pixel, x + 10f, y + h / 2f, 9f, h / 2f - 12f);
        batch.setColor(Color.WHITE);

        Color ink = disabled ? BattleUiAssets.MUTED : BattleUiAssets.TEXT;
        float textX = x + 30f;
        float textW = w - 40f;
        font.setColor(ink);
        drawFitted(batch, font, move.getName(), textX, y + h - 24f, textW, 1);
        font.setColor(disabled ? BattleUiAssets.MUTED : type);
        drawFitted(batch, font, typeName(move.getCategory()), textX, y + h - 48f, textW, 1);

        font.setColor(ink);
        // Description flows from the top-left directly beneath the type tag.
        drawFitted(batch, font, move.getDescription(), textX, y + h - 74f, textW, 6);
        statFont.setColor(ink);
        drawStatRows(batch, statFont, textX, textW, y + 64f, y + 28f,
            accuracyLabel(), move.getBasePower() > 0 ? "PWR " + move.getBasePower() : null,
            "AP " + move.getApCost(), move.hasCeCost() ? "CE " + actualCeCost : null);
    }

    private String accuracyLabel() {
        return move.isNeverMiss()
            ? "ACC N/A"
            : "ACC " + (int) Math.round(move.getBaseAccuracy() * 100d) + "%";
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

    /** Draws every word inside a fixed card area, reducing pixel size only if needed. */
    private static void drawFitted(Batch batch, BitmapFont font, String value, float x, float y,
                                   float maxWidth, int maxLines) {
        String text = value == null || value.isBlank() ? "-" : value;
        float originalScaleX = font.getData().scaleX;
        float originalScaleY = font.getData().scaleY;
        List<String> lines = List.of(text);

        for (float scale = 1f; scale >= 0.30f; scale -= 0.10f) {
            font.getData().setScale(scale);
            lines = wrap(font, text, maxWidth);
            if (lines.size() <= maxLines) break;
        }

        if (lines.size() > maxLines) {
            lines = lines.subList(0, maxLines);
            int last = lines.size() - 1;
            lines.set(last, ellipsize(font, lines.get(last), maxWidth));
        }

        float lineHeight = font.getLineHeight();
        for (int i = 0; i < lines.size(); i++) {
            font.draw(batch, lines.get(i), x, y - i * lineHeight);
        }
        font.getData().setScale(originalScaleX, originalScaleY);
    }

    /** Draws the two stat rows at one shared scale, preserving a clear middle gap. */
    private static void drawStatRows(Batch batch, BitmapFont font, float x, float width,
                                     float upperY, float lowerY, String upperLeft, String upperRight,
                                     String lowerLeft, String lowerRight) {
        float originalScaleX = font.getData().scaleX;
        float originalScaleY = font.getData().scaleY;
        float columnWidth = (width - 16f) / 2f;

        for (float scale = 1f; scale >= 0.30f; scale -= 0.10f) {
            font.getData().setScale(scale);
            if (fits(font, upperLeft, columnWidth) && fits(font, upperRight, columnWidth)
                && fits(font, lowerLeft, columnWidth) && fits(font, lowerRight, columnWidth)) {
                break;
            }
        }

        drawStat(batch, font, upperLeft, x, upperY, false, width);
        drawStat(batch, font, upperRight, x, upperY, true, width);
        drawStat(batch, font, lowerLeft, x, lowerY, false, width);
        drawStat(batch, font, lowerRight, x, lowerY, true, width);
        font.getData().setScale(originalScaleX, originalScaleY);
    }

    private static boolean fits(BitmapFont font, String value, float maxWidth) {
        return value == null || width(font, value) <= maxWidth;
    }

    private static void drawStat(Batch batch, BitmapFont font, String value, float x, float y,
                                 boolean rightAligned, float rowWidth) {
        if (value == null) return;
        float drawX = rightAligned ? x + rowWidth - width(font, value) : x;
        font.draw(batch, value, drawX, y);
        // A one-pixel second pass keeps the larger stats legible in pixel art.
        font.draw(batch, value, drawX + 1f, y);
    }

    private static List<String> wrap(BitmapFont font, String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(font, candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            while (width(font, word) > maxWidth && word.length() > 1) {
                int end = fittingPrefix(font, word, maxWidth);
                lines.add(word.substring(0, end));
                word = word.substring(end);
            }
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.isEmpty() ? List.of("-") : lines;
    }

    private static int fittingPrefix(BitmapFont font, String word, float maxWidth) {
        int end = 1;
        while (end < word.length() && width(font, word.substring(0, end + 1)) <= maxWidth) end++;
        return end;
    }

    private static String ellipsize(BitmapFont font, String value, float maxWidth) {
        String suffix = "...";
        String result = value;
        while (result.length() > 1 && width(font, result + suffix) > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + suffix;
    }

    private static float width(BitmapFont font, String text) {
        return new GlyphLayout(font, text).width;
    }
}
