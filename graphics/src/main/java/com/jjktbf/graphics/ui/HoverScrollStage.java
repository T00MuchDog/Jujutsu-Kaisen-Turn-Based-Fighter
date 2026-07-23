package com.jjktbf.graphics.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.utils.Pools;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * {@link Stage} that routes mouse-wheel/trackpad scroll to whatever actor the cursor
 * is hovering over, instead of to the actor holding scroll focus.
 *
 * <p>The default {@code Stage} delivers scroll events to {@link #getScrollFocus()
 * scrollFocus}, which a {@link ScrollPane} only claims on click/press. That forces a
 * click into a panel before the wheel works there. This subclass fires the scroll
 * event at the topmost touchable actor under the cursor (falling back to the root if
 * the cursor is over empty space), so a panel scrolls the moment the cursor enters
 * it. The event still bubbles to ancestors, so the enclosing scroll pane receives it.
 */
public class HoverScrollStage extends Stage {

    private final Vector2 tempCoords = new Vector2();

    public HoverScrollStage() {
        super();
    }

    public HoverScrollStage(ScreenViewport viewport) {
        super(viewport);
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        screenToStageCoordinates(tempCoords.set(Gdx.input.getX(), Gdx.input.getY()));
        Actor target = hit(tempCoords.x, tempCoords.y, true);
        if (target == null) target = getRoot();

        InputEvent event = Pools.obtain(InputEvent::new);
        event.setType(InputEvent.Type.scrolled);
        event.setStage(this);
        event.setStageX(tempCoords.x);
        event.setStageY(tempCoords.y);
        event.setScrollAmountX(amountX);
        event.setScrollAmountY(amountY);
        target.fire(event);
        boolean handled = event.isHandled();
        Pools.free(event);
        return handled;
    }
}
