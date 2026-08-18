package com.jjktbf.graphics.ui;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.jjktbf.graphics.ui.profile.UiProfile;

import java.util.Objects;

/**
 * A select box whose popup is sized from its list content and kept inside the
 * visible stage area.
 */
public class DynamicSelectBox<T> extends SelectBox<T> {

    private static final float VIEWPORT_MARGIN = 8f;
    private static final float WINDOWS_VIEWPORT_MARGIN = 12f;

    private ContentSizedScrollPane<T> contentSizedScrollPane;

    /** @deprecated Production callers should pass their active UI profile explicitly. */
    @Deprecated
    public DynamicSelectBox(Skin skin) {
        this(skin, UiProfile.MAC);
    }

    public DynamicSelectBox(Skin skin, UiProfile uiProfile) {
        super(skin);
        contentSizedScrollPane.viewportMargin =
            Objects.requireNonNull(uiProfile, "uiProfile") == UiProfile.WINDOWS
                ? WINDOWS_VIEWPORT_MARGIN : VIEWPORT_MARGIN;
    }

    @Override
    protected SelectBoxScrollPane<T> newScrollPane() {
        contentSizedScrollPane = new ContentSizedScrollPane<>(this, VIEWPORT_MARGIN);
        return contentSizedScrollPane;
    }

    private static final class ContentSizedScrollPane<T> extends SelectBoxScrollPane<T> {

        private final Vector2 stagePosition = new Vector2();
        private float viewportMargin;

        private ContentSizedScrollPane(SelectBox<T> selectBox, float viewportMargin) {
            super(selectBox);
            this.viewportMargin = viewportMargin;
        }

        @Override
        public void show(Stage stage) {
            super.show(stage);
            if (!hasParent() || getSelectBox().getMaxListCount() > 0) return;

            SelectBox<T> selectBox = getSelectBox();
            selectBox.localToStageCoordinates(stagePosition.set(0f, 0f));

            float margin = Math.min(viewportMargin,
                Math.min(stage.getWidth(), stage.getHeight()) / 2f);
            float preferredHeight = getPrefHeight();
            float spaceBelow = Math.max(0f, stagePosition.y - margin);
            float spaceAbove = Math.max(0f,
                stage.getHeight() - stagePosition.y - selectBox.getHeight() - margin);
            boolean opensBelow = preferredHeight <= spaceBelow
                || (preferredHeight > spaceAbove && spaceBelow >= spaceAbove);
            float availableHeight = opensBelow ? spaceBelow : spaceAbove;

            // On an extremely small viewport, overlap the control rather than
            // creating a zero-height popup.
            if (availableHeight == 0f) {
                availableHeight = Math.max(0f, stage.getHeight() - margin * 2f);
                opensBelow = true;
            }

            float height = Math.min(preferredHeight, availableHeight);
            float width = Math.min(Math.max(getPrefWidth(), selectBox.getWidth()),
                Math.max(0f, stage.getWidth() - margin * 2f));
            float maxX = Math.max(margin, stage.getWidth() - margin - width);
            float maxY = Math.max(margin, stage.getHeight() - margin - height);
            float x = MathUtils.clamp(stagePosition.x, margin, maxX);
            float y = opensBelow ? stagePosition.y - height : stagePosition.y + selectBox.getHeight();

            setBounds(x, MathUtils.clamp(y, margin, maxY), width, height);
            validate();

            int selectedIndex = selectBox.getSelectedIndex();
            if (selectedIndex >= 0) {
                float itemHeight = getList().getItemHeight();
                scrollTo(0f, getList().getHeight() - selectedIndex * itemHeight - itemHeight / 2f,
                    0f, 0f, true, true);
                updateVisualScroll();
            }
        }
    }
}
