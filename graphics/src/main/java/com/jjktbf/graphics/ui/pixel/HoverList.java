package com.jjktbf.graphics.ui.pixel;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.List;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;

/**
 * A {@link List} variant that paints the hovered (moused-over) item's text in a
 * highlight colour.
 *
 * The stock LibGDX {@code List} only distinguishes selected vs unselected text
 * colour — it tracks {@code overIndex} internally but exposes no per-item hover
 * colour, and the field is package-private so subclasses outside
 * {@code com.badlogic.gdx} can't read it. This subclass tracks the pointer
 * itself and overrides {@link #drawItem} to swap the font colour for the item
 * currently under the pointer.
 *
 * Used for the editor master lists so every selectable item (move, character,
 * ability) turns bright yellow on hover.
 */
public class HoverList<T> extends List<T> {

    private Color hoverColor = Color.YELLOW;
    private int hoverIndex = -1;

    public HoverList(Skin skin) {
        super(skin);
        installHoverTracking();
    }

    public HoverList(Skin skin, String styleName) {
        super(skin, styleName);
        installHoverTracking();
    }

    public HoverList(com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle style) {
        super(style);
        installHoverTracking();
    }

    /** Set the colour applied to the item under the pointer (default yellow). */
    public void setHoverColor(Color c) {
        this.hoverColor = c;
    }

    private void installHoverTracking() {
        addListener(new InputListener() {
            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                updateHover(y);
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                hoverIndex = -1;
            }

            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                updateHover(y);
                return false;
            }
        });
    }

    private void updateHover(float y) {
        T item = getItemAt(y);
        hoverIndex = (item != null) ? getItems().indexOf(item, false) : -1;
    }

    @Override
    protected GlyphLayout drawItem(Batch batch, BitmapFont font, int index, T item,
                                   float x, float y, float width) {
        // font.getColor() returns a live reference to the font's internal Color,
        // not a copy — so capture by value, otherwise setColor(hoverColor) below
        // also overwrites prev and the restore becomes a no-op (leaking the hover
        // colour onto every item drawn after this one).
        Color prev = new Color(font.getColor());
        if (index == hoverIndex && index != getSelectedIndex()) {
            font.setColor(hoverColor);
        }
        GlyphLayout gl = super.drawItem(batch, font, index, item, x, y, width);
        font.setColor(prev);
        return gl;
    }
}
