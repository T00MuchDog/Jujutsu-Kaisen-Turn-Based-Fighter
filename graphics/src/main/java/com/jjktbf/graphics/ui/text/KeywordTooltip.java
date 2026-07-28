package com.jjktbf.graphics.ui.text;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.utils.Align;
import com.jjktbf.model.text.KeywordDescriptionCatalog;

/** A stage-level popup shared by keyword labels in a clipped editor surface. */
public final class KeywordTooltip {

    private static final float CONTENT_WIDTH = 280f;

    private final Table popup;
    private final Label heading;
    private final Label description;
    private final Cell<Label> headingCell;
    private final Cell<Label> descriptionCell;
    private Actor owner;

    public KeywordTooltip(Skin skin) {
        popup = new Table(skin);
        popup.setBackground(skin.getDrawable("battle-card-over"));
        popup.setTouchable(Touchable.disabled);
        popup.pad(10f);

        heading = new Label("", skin, "white");
        heading.setColor(KeywordTextLayout.KEYWORD_ORANGE);
        heading.setEllipsis(true);
        description = new Label("", skin, "small");
        description.setWrap(true);
        description.setAlignment(Align.topLeft);

        headingCell = popup.add(heading).width(CONTENT_WIDTH).left();
        popup.row();
        descriptionCell = popup.add(description)
            .width(CONTENT_WIDTH).left().top().padTop(5f);
        popup.row();
    }

    public void show(
        Actor source,
        String displayedTerm,
        KeywordDescriptionCatalog.Entry entry,
        Rectangle stageWordBounds
    ) {
        Stage stage = source == null ? null : source.getStage();
        if (stage == null || entry == null || stageWordBounds == null) {
            hide();
            return;
        }

        owner = source;
        heading.setText(displayedTerm);
        description.setText(entry.description());
        float contentWidth = Math.max(1f, Math.min(CONTENT_WIDTH, stage.getWidth() - 20f));
        headingCell.width(contentWidth);
        descriptionCell.width(contentWidth);
        heading.setWidth(contentWidth);
        description.setWidth(contentWidth);
        description.invalidateHierarchy();
        popup.invalidateHierarchy();
        popup.pack();

        KeywordPopupPosition.Position position = KeywordPopupPosition.place(
            stageWordBounds.x,
            stageWordBounds.y,
            stageWordBounds.width,
            stageWordBounds.height,
            popup.getWidth(),
            popup.getHeight(),
            stage.getWidth(),
            stage.getHeight());
        popup.setPosition(position.x(), position.y());
        if (popup.getStage() != stage) {
            popup.remove();
            stage.addActor(popup);
        }
        popup.toFront();
    }

    public void hide(Actor source) {
        if (source == owner) hide();
    }

    public void hide() {
        popup.remove();
        owner = null;
    }
}
