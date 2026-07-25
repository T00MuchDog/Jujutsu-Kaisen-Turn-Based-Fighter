package com.jjktbf.graphics.ui;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;

/** A dialog that follows its content's preferred size while it is open. */
public class ContentSizedDialog extends Dialog {

    private static final float VIEWPORT_MARGIN = 12f;

    public ContentSizedDialog(String title, Skin skin) {
        super(title, skin);
    }

    @Override
    public ContentSizedDialog show(Stage stage) {
        super.show(stage);
        resizeToContent(stage, true);
        return this;
    }

    @Override
    public void act(float delta) {
        super.act(delta);

        Stage stage = getStage();
        if (stage == null) return;

        // Container#setActor does not invalidate its parent tables. Recalculate
        // here so changing an option can grow or shrink the open dialog.
        getContentTable().invalidate();
        getButtonTable().invalidate();
        invalidate();
        resizeToContent(stage, false);
    }

    private void resizeToContent(Stage stage, boolean centerOnStage) {
        float margin = Math.min(VIEWPORT_MARGIN,
            Math.min(stage.getWidth(), stage.getHeight()) / 2f);
        float width = Math.min(getPrefWidth(), Math.max(0f, stage.getWidth() - margin * 2f));
        float height = Math.min(getPrefHeight(), Math.max(0f, stage.getHeight() - margin * 2f));

        boolean sizeChanged = Math.abs(getWidth() - width) > 0.5f
            || Math.abs(getHeight() - height) > 0.5f;
        if (!sizeChanged && !centerOnStage) return;

        float centerX = centerOnStage ? stage.getWidth() / 2f : getX() + getWidth() / 2f;
        float centerY = centerOnStage ? stage.getHeight() / 2f : getY() + getHeight() / 2f;
        float maxX = Math.max(margin, stage.getWidth() - margin - width);
        float maxY = Math.max(margin, stage.getHeight() - margin - height);

        setSize(width, height);
        setPosition(
            MathUtils.clamp(centerX - width / 2f, margin, maxX),
            MathUtils.clamp(centerY - height / 2f, margin, maxY)
        );
        validate();
    }
}
