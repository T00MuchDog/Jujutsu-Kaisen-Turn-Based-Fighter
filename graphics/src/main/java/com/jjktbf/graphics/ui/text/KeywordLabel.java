package com.jjktbf.graphics.ui.text;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;

/** Scene2D text widget that highlights and exposes glossary keywords. */
public final class KeywordLabel extends Widget {

    private final BitmapFont font;
    private final KeywordTooltip tooltip;
    private final Color baseColor;
    private String text;
    private KeywordTextLayout textLayout;
    private float layoutWidth = -1f;

    public KeywordLabel(String text, Label.LabelStyle style, KeywordTooltip tooltip) {
        this.text = text == null ? "" : text;
        this.font = style.font;
        this.baseColor = new Color(style.fontColor == null ? Color.WHITE : style.fontColor);
        this.tooltip = tooltip;
        addListener(new InputListener() {
            @Override public boolean mouseMoved(InputEvent event, float x, float y) {
                return updateTooltip(x, y);
            }

            @Override public void enter(
                InputEvent event, float x, float y, int pointer, Actor fromActor
            ) {
                if (pointer == -1) updateTooltip(x, y);
            }

            @Override public void exit(
                InputEvent event, float x, float y, int pointer, Actor toActor
            ) {
                if (pointer == -1) tooltip.hide(KeywordLabel.this);
            }
        });
    }

    public void setBaseColor(Color color) {
        baseColor.set(color == null ? Color.WHITE : color);
    }

    @Override
    public void layout() {
        rebuildLayout();
    }

    @Override
    public float getPrefHeight() {
        if (getWidth() > 0f) rebuildLayout();
        return textLayout == null ? font.getLineHeight() : textLayout.height();
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        validate();
        Color normal = new Color(baseColor);
        normal.a *= getColor().a * parentAlpha;
        Color keyword = new Color(KeywordTextLayout.KEYWORD_ORANGE);
        keyword.a *= getColor().a * parentAlpha;
        textLayout.draw(batch, font, getX(), getY() + getHeight(), normal, keyword);
    }

    private void rebuildLayout() {
        float width = Math.max(1f, getWidth());
        if (textLayout != null && Math.abs(layoutWidth - width) < 0.01f) return;
        textLayout = KeywordTextLayout.build(font, text, width, Integer.MAX_VALUE, 1f, 0.9f);
        layoutWidth = width;
    }

    private boolean updateTooltip(float x, float y) {
        validate();
        KeywordTextLayout.KeywordHit hit = textLayout.keywordAt(x, y - getHeight());
        if (hit == null) {
            tooltip.hide(this);
            return false;
        }

        Rectangle bounds = hit.bounds();
        Vector2 bottomLeft = localToStageCoordinates(
            new Vector2(bounds.x, getHeight() + bounds.y));
        Vector2 topRight = localToStageCoordinates(
            new Vector2(bounds.x + bounds.width, getHeight() + bounds.y + bounds.height));
        Rectangle stageBounds = new Rectangle(
            Math.min(bottomLeft.x, topRight.x),
            Math.min(bottomLeft.y, topRight.y),
            Math.abs(topRight.x - bottomLeft.x),
            Math.abs(topRight.y - bottomLeft.y));
        tooltip.show(this, hit.text(), hit.entry(), stageBounds);
        return true;
    }
}
