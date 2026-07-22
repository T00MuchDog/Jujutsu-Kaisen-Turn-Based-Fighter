package com.jjktbf.graphics.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.jjktbf.graphics.ui.battle.BattleUiAssets;
import com.jjktbf.model.character.coded.CodedAbilityState;
import com.jjktbf.model.character.coded.MiraclesAbility;

/** Player-visible Miracles counter graphic for the Miracles coded ability. */
public final class MiraclesMeter {

    private static final float GRAPHIC_SCALE = 1.8f;

    private float x;
    private float y;
    private float size;
    private int imageIndex;
    private boolean visible;

    /** Returns the responsive height used by planner timeline bars. */
    public static float timelineHeightForViewport(float viewportHeight) {
        return Math.min(84f, Math.max(54f, viewportHeight * 0.115f));
    }

    /** Makes the counter graphic 80% larger than a planner timeline bar. */
    public static float sizeForViewport(float viewportHeight) {
        return timelineHeightForViewport(viewportHeight) * GRAPHIC_SCALE;
    }

    /** Positions the square counter graphic. */
    public void setBounds(float x, float y, float size) {
        this.x = x;
        this.y = y;
        this.size = size;
    }

    /** Shows the graphic only when the player has a live Miracles runtime. */
    public void setState(CodedAbilityState state) {
        if (state == null || !MiraclesAbility.KEY.equals(state.key())) {
            clear();
            return;
        }
        visible = true;
        imageIndex = Math.max(0, Math.min(MiraclesAbility.MAX_MIRACLES, state.currentValue()));
    }

    public void clear() {
        visible = false;
        imageIndex = 0;
    }

    public boolean isVisible() {
        return visible;
    }

    int imageIndex() {
        return imageIndex;
    }

    /** Draws the supplied 0-6 Miracles graphic selected from the live ability state. */
    public void draw(Batch batch, BattleUiAssets ui) {
        if (!visible) return;

        Color previousBatchColor = new Color(batch.getColor());
        batch.setColor(Color.WHITE);
        batch.draw(ui.miracleCounter(imageIndex), x, y, size, size);
        batch.setColor(previousBatchColor);
    }
}
