package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;

/**
 * {@link ScrollPane} variant that never cross-routes scroll axes.
 *
 * <p>The default {@code ScrollPane} maps an orthogonal wheel/trackpad gesture onto
 * the pane's single enabled axis (a horizontal gesture scrolls a vertical-only pane,
 * and vice-versa). That is undesirable here — a two-finger gesture should only move
 * the pane along its matching axis — so this subclass reproduces the default scroll
 * math verbatim minus that axis-swapping step. Vertical input only ever feeds
 * {@code setScrollY}; horizontal input only ever feeds {@code setScrollX}.
 */
public class AxisLockedScrollPane extends ScrollPane {

    public AxisLockedScrollPane(Actor actor) {
        super(actor);
    }

    public AxisLockedScrollPane(Actor actor, Skin skin) {
        super(actor, skin);
    }

    public AxisLockedScrollPane(Actor actor, ScrollPaneStyle style) {
        super(actor, style);
    }

    @Override
    protected void addScrollListener() {
        addListener(new InputListener() {
            @Override
            public boolean scrolled(
                InputEvent event, float x, float y, float scrollAmountX, float scrollAmountY
            ) {
                event.cancel();
                setScrollbarsVisible(true);
                if (!isScrollX() && !isScrollY()) return false;
                // Snap the gesture to its dominant axis so a trackpad swipe only moves
                // content along the direction it actually travelled, then route each axis
                // strictly to its matching scroll axis (no axis swapping).
                float[] dominant = ScrollAxes.dominant(scrollAmountX, scrollAmountY);
                if (isScrollY()) setScrollY(getScrollY() + getMouseWheelY() * dominant[1]);
                if (isScrollX()) setScrollX(getScrollX() + getMouseWheelX() * dominant[0]);
                return true;
            }
        });
    }
}
