package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;
import com.jjktbf.graphics.ui.pixel.HoverList;
import com.jjktbf.graphics.ui.text.KeywordTextLayout;
import com.jjktbf.model.text.KeywordDescriptionCatalog;

import java.util.List;

/** Text field that suggests documented keywords for uppercase input. */
public final class KeywordAutocompleteField extends HoverTextField {

    private static final float CONTENT_WIDTH = 420f;
    private static final float VIEWPORT_MARGIN = 8f;
    private static final int MAX_VISIBLE_ITEMS = 7;

    private final List<KeywordDescriptionCatalog.Entry> catalogEntries;
    private final Table popup;
    private final HoverList<String> suggestionList;
    private final ScrollPane suggestionScroll;
    private final Label selectedTerm;
    private final Label description;
    private final Cell<Label> headingCell;
    private final Cell<Label> controlsCell;
    private final Cell<ScrollPane> suggestionCell;
    private final Cell<Label> selectedTermCell;
    private final Cell<Label> descriptionCell;
    private final Vector2 fieldBottomLeft = new Vector2();
    private final Vector2 fieldTopRight = new Vector2();
    private final Vector2 clipBottomLeft = new Vector2();
    private final Vector2 clipTopRight = new Vector2();

    private KeywordAutocomplete.Query query;
    private List<KeywordDescriptionCatalog.Entry> suggestions = List.of();
    private String observedText;
    private int observedCursor = -1;
    private float packedStageWidth = -1f;
    private float packedStageHeight = -1f;
    private int previewIndex = -1;
    private boolean suppressNextCompletionCharacter;

    public KeywordAutocompleteField(String text, Skin skin) {
        this(text, skin, KeywordDescriptionCatalog.getDefault().entries());
    }

    KeywordAutocompleteField(
        String text,
        Skin skin,
        List<KeywordDescriptionCatalog.Entry> catalogEntries
    ) {
        super(text, skin);
        this.catalogEntries = List.copyOf(catalogEntries == null ? List.of() : catalogEntries);

        popup = new Table(skin);
        popup.setBackground(skin.getDrawable("battle-card-over"));
        popup.pad(10f);
        popup.setClip(true);

        Label heading = new Label("KEYWORDS", skin);
        heading.setColor(KeywordTextLayout.KEYWORD_ORANGE);
        headingCell = popup.add(heading).left();
        popup.row();
        Label controls = new Label("UP/DOWN: select | ENTER/TAB: insert | ESC: close", skin, "small");
        controls.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
        controls.setWrap(true);
        controlsCell = popup.add(controls).left();
        popup.row();

        suggestionList = new HoverList<>(skin);
        suggestionList.setHoverColor(KeywordTextLayout.KEYWORD_ORANGE);
        suggestionScroll = new ScrollPane(suggestionList, skin, "dropdown");
        suggestionScroll.setFadeScrollBars(false);
        suggestionScroll.setScrollingDisabled(true, false);
        suggestionScroll.setOverscroll(false, false);
        suggestionCell = popup.add(suggestionScroll).left().growX().padTop(6f);
        popup.row();

        selectedTerm = new Label("", skin);
        selectedTerm.setColor(KeywordTextLayout.KEYWORD_ORANGE);
        selectedTermCell = popup.add(selectedTerm).left().padTop(8f);
        popup.row();

        description = new Label("", skin, "small");
        description.setWrap(true);
        description.setAlignment(Align.topLeft);
        descriptionCell = popup.add(description).left().top().padTop(3f);
        popup.row();

        suggestionList.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                updateDescription();
            }
        });
        suggestionList.addListener(new InputListener() {
            @Override public boolean mouseMoved(InputEvent event, float x, float y) {
                int hovered = suggestionList.getItemIndexAt(y);
                if (hovered != previewIndex) {
                    previewIndex = hovered;
                    updateDescription();
                }
                return false;
            }

            @Override public void exit(
                InputEvent event,
                float x,
                float y,
                int pointer,
                Actor toActor
            ) {
                if (previewIndex < 0) return;
                previewIndex = -1;
                updateDescription();
            }
        });
        suggestionList.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                insertSelectedSuggestion();
            }
        });
        addCaptureListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int keycode) {
                synchronizeSuggestions();
                if (!suggestionsVisible()) return false;
                if (keycode == Input.Keys.UP || keycode == Input.Keys.DOWN) {
                    moveSelection(keycode == Input.Keys.UP ? -1 : 1);
                } else if (keycode == Input.Keys.ENTER || keycode == Input.Keys.TAB) {
                    insertSelectedSuggestion();
                    suppressNextCompletionCharacter = true;
                } else {
                    return false;
                }
                event.cancel();
                return true;
            }

            @Override public boolean keyTyped(InputEvent event, char character) {
                if (!suppressNextCompletionCharacter) return false;
                suppressNextCompletionCharacter = false;
                if (character != '\t' && character != '\n' && character != '\r') return false;
                event.cancel();
                return true;
            }
        });
        addListener(new FocusListener() {
            @Override public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                if (focused) {
                    observedText = null;
                    observedCursor = -1;
                } else if (ownsSuggestionActor(event.getRelatedActor())) {
                    // A bare LibGDX List requests keyboard focus on mouse-down.
                    // Keep focus in the text field so the click can insert the item.
                    event.cancel();
                } else {
                    suppressNextCompletionCharacter = false;
                    hideSuggestions();
                }
            }
        });
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        Stage stage = getStage();
        if (stage == null || stage.getKeyboardFocus() != this || isDisabled()) {
            hideSuggestions();
            return;
        }
        if (!fieldIsVisible(stage)) {
            hideSuggestions();
            observedText = null;
            observedCursor = -1;
            return;
        }

        synchronizeSuggestions();
        if (suggestionsVisible()) positionPopup(stage);
    }

    /** Dismisses an open menu and reports whether Escape was consumed. */
    public boolean dismissSuggestions() {
        if (!suggestionsVisible()) return false;
        hideSuggestions();
        return true;
    }

    /** True when an actor belongs to this field's stage-level suggestion menu. */
    public boolean ownsSuggestionActor(Actor actor) {
        return actor != null && (actor == popup || popup.isAscendantOf(actor));
    }

    @Override
    public boolean remove() {
        hideSuggestions();
        return super.remove();
    }

    @Override
    protected void setStage(Stage stage) {
        if (stage == null) hideSuggestions();
        super.setStage(stage);
    }

    private void refreshSuggestions(Stage stage) {
        KeywordAutocomplete.Query next = KeywordAutocomplete.query(
            getText(), getCursorPosition(), catalogEntries);
        if (next == null) {
            hideSuggestions();
            return;
        }

        query = next;
        suggestions = next.matches();
        previewIndex = -1;
        suggestionList.setItems(suggestions.stream()
            .map(KeywordAutocomplete::insertionText)
            .toArray(String[]::new));
        suggestionList.setSelectedIndex(0);
        updateDescription();
        packPopup(stage);
        if (popup.getStage() != stage) {
            popup.remove();
            stage.addActor(popup);
        }
        popup.toFront();
        positionPopup(stage);
    }

    private void moveSelection(int direction) {
        if (suggestions.isEmpty()) return;
        previewIndex = -1;
        int selected = suggestionList.getSelectedIndex();
        int next = Math.floorMod(selected + direction, suggestions.size());
        suggestionList.setSelectedIndex(next);
        updateDescription();
        suggestionScroll.validate();
        float itemHeight = suggestionList.getItemHeight();
        suggestionScroll.scrollTo(
            0f,
            suggestionList.getHeight() - next * itemHeight - itemHeight,
            0f,
            itemHeight,
            true,
            true);
        suggestionScroll.updateVisualScroll();
    }

    private void updateDescription() {
        int selected = previewIndex >= 0
            ? previewIndex
            : suggestionList.getSelectedIndex();
        if (selected < 0 || selected >= suggestions.size()) {
            selectedTerm.setText("");
            description.setText("");
            return;
        }
        KeywordDescriptionCatalog.Entry entry = suggestions.get(selected);
        selectedTerm.setText(KeywordAutocomplete.insertionText(entry));
        description.setText(entry.description());
        Stage stage = getStage();
        if (stage != null) packPopup(stage);
    }

    private void insertSelectedSuggestion() {
        synchronizeSuggestions();
        int selected = suggestionList.getSelectedIndex();
        if (query == null || selected < 0 || selected >= suggestions.size()) return;

        String term = KeywordAutocomplete.insertionText(suggestions.get(selected));
        String completed = KeywordAutocomplete.complete(getText(), query, term);
        int completedCursor = query.start() + term.length();
        boolean previousProgrammaticEvents = getProgrammaticChangeEvents();
        try {
            setProgrammaticChangeEvents(true);
            setText(completed);
        } finally {
            setProgrammaticChangeEvents(previousProgrammaticEvents);
        }
        setCursorPosition(completedCursor);
        observedText = completed;
        observedCursor = completedCursor;
        hideSuggestions();
        Stage stage = getStage();
        if (stage != null) stage.setKeyboardFocus(this);
    }

    private void packPopup(Stage stage) {
        float maximumWidth = Math.max(1f, stage.getWidth() - VIEWPORT_MARGIN * 2f);
        float contentWidth = Math.max(1f,
            Math.min(CONTENT_WIDTH, maximumWidth - 20f));
        float listHeight = Math.min(
            suggestionList.getPrefHeight(),
            suggestionList.getItemHeight() * MAX_VISIBLE_ITEMS + 8f);
        headingCell.width(contentWidth);
        controlsCell.width(contentWidth);
        suggestionCell.width(contentWidth).height(Math.max(suggestionList.getItemHeight(), listHeight));
        selectedTermCell.width(contentWidth);
        descriptionCell.width(contentWidth);
        selectedTerm.setWidth(contentWidth);
        description.setWidth(contentWidth);
        description.invalidateHierarchy();
        popup.invalidateHierarchy();
        popup.pack();

        float maximumHeight = Math.max(1f, stage.getHeight() - VIEWPORT_MARGIN * 2f);
        if (popup.getHeight() > maximumHeight) {
            float reducedListHeight = Math.max(
                suggestionList.getItemHeight(),
                listHeight - (popup.getHeight() - maximumHeight));
            suggestionCell.height(reducedListHeight);
            popup.invalidateHierarchy();
            popup.pack();
        }
        popup.setSize(
            Math.min(popup.getWidth(), maximumWidth),
            Math.min(popup.getHeight(), maximumHeight));
        popup.validate();
        packedStageWidth = stage.getWidth();
        packedStageHeight = stage.getHeight();
    }

    private void positionPopup(Stage stage) {
        if (Math.abs(packedStageWidth - stage.getWidth()) > 0.01f
            || Math.abs(packedStageHeight - stage.getHeight()) > 0.01f) {
            packPopup(stage);
        }
        localToStageCoordinates(fieldBottomLeft.set(0f, 0f));
        localToStageCoordinates(fieldTopRight.set(getWidth(), getHeight()));

        float maxX = Math.max(VIEWPORT_MARGIN,
            stage.getWidth() - VIEWPORT_MARGIN - popup.getWidth());
        float x = MathUtils.clamp(fieldBottomLeft.x, VIEWPORT_MARGIN, maxX);
        float below = fieldBottomLeft.y - popup.getHeight() - 2f;
        float above = fieldTopRight.y + 2f;
        float y = below >= VIEWPORT_MARGIN ? below : above;
        float maxY = Math.max(VIEWPORT_MARGIN,
            stage.getHeight() - VIEWPORT_MARGIN - popup.getHeight());
        popup.setPosition(
            x,
            MathUtils.clamp(y, VIEWPORT_MARGIN, maxY));
        popup.toFront();
    }

    private boolean suggestionsVisible() {
        return popup.getStage() != null && !suggestions.isEmpty();
    }

    private void synchronizeSuggestions() {
        Stage stage = getStage();
        if (stage == null || stage.getKeyboardFocus() != this || !fieldIsVisible(stage)) {
            hideSuggestions();
            return;
        }
        String text = getText();
        int cursor = getCursorPosition();
        if (text.equals(observedText) && cursor == observedCursor) return;
        observedText = text;
        observedCursor = cursor;
        refreshSuggestions(stage);
    }

    private boolean fieldIsVisible(Stage stage) {
        if (!isVisible() || getWidth() <= 0f || getHeight() <= 0f) return false;
        updateFieldBounds();
        if (!overlaps(
            fieldBottomLeft.x, fieldBottomLeft.y, fieldTopRight.x, fieldTopRight.y,
            0f, 0f, stage.getWidth(), stage.getHeight())) {
            return false;
        }

        Actor ancestor = getParent();
        while (ancestor != null) {
            if (!ancestor.isVisible()) return false;
            if (ancestor instanceof ScrollPane) {
                ancestor.localToStageCoordinates(clipBottomLeft.set(0f, 0f));
                ancestor.localToStageCoordinates(
                    clipTopRight.set(ancestor.getWidth(), ancestor.getHeight()));
                if (!overlaps(
                    fieldBottomLeft.x, fieldBottomLeft.y, fieldTopRight.x, fieldTopRight.y,
                    clipBottomLeft.x, clipBottomLeft.y, clipTopRight.x, clipTopRight.y)) {
                    return false;
                }
            }
            ancestor = ancestor.getParent();
        }
        return true;
    }

    private void updateFieldBounds() {
        localToStageCoordinates(fieldBottomLeft.set(0f, 0f));
        localToStageCoordinates(fieldTopRight.set(getWidth(), getHeight()));
    }

    private static boolean overlaps(
        float firstLeft,
        float firstBottom,
        float firstRight,
        float firstTop,
        float secondLeft,
        float secondBottom,
        float secondRight,
        float secondTop
    ) {
        return firstRight > secondLeft && firstLeft < secondRight
            && firstTop > secondBottom && firstBottom < secondTop;
    }

    private void hideSuggestions() {
        popup.remove();
        query = null;
        suggestions = List.of();
        previewIndex = -1;
        packedStageWidth = -1f;
        packedStageHeight = -1f;
    }
}
