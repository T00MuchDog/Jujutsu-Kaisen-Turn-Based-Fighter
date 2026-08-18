package com.jjktbf.graphics.ui.editor;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.ui.HoverScrollStage;
import com.jjktbf.graphics.ui.pixel.HoverList;
import com.jjktbf.graphics.ui.profile.UiProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared chrome for the three graphical CRUD editors (Moves, Characters, Abilities).
 *
 * Layout (Pokémon-Showdown-style master-detail):
 *
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │ TITLE                                  [New][Dup][Del][Back] │  toolbar
 *   ├──────────────┬────────────────────────────────────────────────┤
 *   │ master list  │                                                │
 *   │ (scrollable) │            detail form (scrollable)            │
 *   │  + search    │                                                │
 *   ├──────────────┴────────────────────────────────────────────────┤
 *   │ [* dirty]   [Save] [Cancel]        status/error message        │  action bar
 *   └─────────────────────────────────────────────────────────────┘
 *
 * Behaviour:
 *   - Edits live in an in-memory draft. Nothing reaches JSON until Save is
 *     clicked, which calls {@link #validateAndSave(Object)}.
 *   - Save failures show the validation message in the status bar.
 *   - The list is click-to-select, mouse-wheel scrollable (via ScrollPane), and
 *     arrow-key navigable.
 *   - Delete asks for confirmation via {@link #confirmDelete(String, Runnable)}.
 *
 * Subclasses implement the {@code abstract} hooks to bind their DTO + form.
 *
 * @param <D>  the DTO type this editor edits (MoveData / CharacterData / AbilityData)
 */
public abstract class EditorScreenBase<D> implements Screen {

    // ── Layout constants ───────────────────────────────────────────────────────

    /** Width fraction of the master list (left). */
    private static final float LIST_W_FRAC = 0.30f;
    private static final float PAD = 8f;
    /** Horizontal offset applied once for each nested record-section level. */
    private static final float RECORD_SUBSECTION_INDENT = 20f;
    /** Top-level section headers are larger than, but distinct from, subsections. */
    private static final float RECORD_SECTION_HEAD_TITLE_SCALE =
        1.5f / AssetLoader.FONT_OVERSAMPLE;
    private static final float RECORD_SECTION_HEAD_VERTICAL_PADDING = 9f;
    private static final float RECORD_SUBSECTION_VERTICAL_PADDING = 6f;
    /** Minimum width of the label column in form rows, for cross-row alignment. */
    private static final float FORM_LABEL_WIDTH = 200f;
    /** Delay before a held record-navigation key begins repeating. */
    private static final float RECORD_KEY_REPEAT_DELAY = 0.50f;
    /** Repeat cadence for held record-navigation keys (12.5 records per second). */
    private static final float RECORD_KEY_REPEAT_INTERVAL = 0.08f;

    // ── Injected deps ──────────────────────────────────────────────────────────

    protected final JJKGame     game;
    protected final AssetLoader assets;
    protected final Skin        skin;
    protected final UiProfile   uiProfile;
    protected final boolean     windowsLayout;

    // ── Stage + root ───────────────────────────────────────────────────────────

    protected final Stage stage;
    protected final Table root;

    /** Guards against double-dispose of native stage resources. */
    private boolean disposed;

    // ── UI handles ─────────────────────────────────────────────────────────────

    /** Search box above the master list. */
    protected TextField searchField;
    /** The list of names shown in the master panel. Hover-highlighted (bright yellow). */
    protected final HoverList<String> masterList;
    /** ScrollPane wrapping the master list. */
    protected AxisLockedScrollPane masterScroll;
    /** Scrollable content containing either the flat list or categorized sections. */
    private final Table masterListContent;
    /** Pinned copies of section headers that have reached the top of the list. */
    private final Table stickySectionHeaders;
    /** Layout cell for the master pane, resized with the viewport. */
    private Cell<?> masterColumn;
    /** Container holding the detail form on the right. Cleared on selection change. */
    protected Container<Actor> detailContainer;
    /** Status / error label at the bottom. */
    protected Label statusLabel;
    /** Dirty indicator. */
    protected Label dirtyLabel;
    /** Save / Cancel buttons (toggled disabled when no selection). */
    protected TextButton saveButton;
    protected TextButton cancelButton;

    // ── State ──────────────────────────────────────────────────────────────────

    /** All records currently in the repo (refreshed on load/save/delete). */
    protected List<D> records = new ArrayList<>();
    /** Index into {@link #records} of the currently selected record, or -1. */
    protected int selectedIndex = -1;
    /** The in-memory draft being edited. */
    protected D draft;
    /** True if any field of the draft has changed since load/last-save. */
    protected boolean dirty = false;
    /** True while we are rebuilding the detail form (suppresses dirty marking). */
    protected boolean suppressDirty = false;

    /** Record IDs in the current filtered/sorted master-list order. */
    private final List<String> visibleRecordIds = new ArrayList<>();
    /** Active lists and their local record-ID order. Sectioned editors have one per section. */
    private final Map<HoverList<String>, List<String>> recordIdsByList = new IdentityHashMap<>();
    private final List<HoverList<String>> masterRecordLists = new ArrayList<>();
    private final List<MasterSectionView> masterSectionViews = new ArrayList<>();
    private final Map<String, Boolean> collapsedRecordSections = new LinkedHashMap<>();
    private List<String> renderedStickySections = List.of();
    private boolean suppressMasterListEvents;
    /** Suppresses record-open audio for keyboard and programmatic list changes. */
    private boolean suppressRecordSelectionSound;
    /** Arrow key currently driving repeated master-list navigation, or -1. */
    private int heldRecordKey = -1;
    /** Remaining time before the next held-key navigation step. */
    private float recordKeyRepeatTimer;

    // =========================================================================
    // Construction
    // =========================================================================

    protected EditorScreenBase(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.skin   = assets.editorSkin;
        this.uiProfile = game.activeUiProfile();
        this.windowsLayout = uiProfile == UiProfile.WINDOWS;
        this.stage  = new HoverScrollStage(new ScreenViewport());

        this.root = new Table();
        this.root.setFillParent(true);
        this.root.pad(20f);
        this.stage.addActor(root);

        this.masterList = new HoverList<>(skin);
        this.masterListContent = new Table(skin);
        this.masterListContent.top();
        this.stickySectionHeaders = new Table(skin);
        this.stickySectionHeaders.top();
        this.stickySectionHeaders.setTouchable(Touchable.childrenOnly);
        buildChrome();
        wireInput();
    }

    // =========================================================================
    // Abstract hooks — subclasses implement these
    // =========================================================================

    /** Screen title shown top-left (e.g. "MOVE EDITOR"). */
    protected abstract String title();

    /** A fresh blank draft for a new record. */
    protected abstract D newDraft();

    /** Make a working copy of a stored record for editing. */
    protected abstract D draftFromRecord(D stored);

    /** Unique id of a record (for selection tracking). */
    protected abstract String idOf(D record);

    /**
     * The id the next new record will receive ({@code formatId(store.size())}).
     * Used to pre-fill a new/copy draft's id so engine validation (which
     * rejects blank ids) passes before the repo assigns the real id on add.
     */
    protected abstract String nextId();

    /** Human-readable list label for a record. */
    protected abstract String listLabel(D record);

    /** Ordered record sections. An empty list keeps the traditional flat master list. */
    protected List<String> recordSections() { return List.of(); }

    /** Section name for a record when {@link #recordSections()} is non-empty. */
    protected String recordSection(D record) { return null; }

    /**
     * Optional parent for a record section. A collapsed parent hides all of its
     * child section headers and records while preserving each child's own state.
     */
    protected String recordSectionParent(String section) { return null; }

    /** User-facing title for a record section. */
    protected String recordSectionLabel(String section) { return section; }

    /**
     * Build the detail form Actor for the current draft. Called every time the
     * selection changes or a new record is created. Use {@link #markDirty()} on
     * any field change.
     */
    protected abstract Actor buildDetailForm(D d);

    /**
     * Validate the draft and persist it. Return {@link ValidationResult#ok()}
     * or {@link ValidationResult#error(String)}. Implementations should:
     *   - call repo.update(draft) if editing, repo.add(draft) if creating
     *   - call repo.save()
     *   - catch IOException / validation exceptions and convert to error()
     */
    protected abstract ValidationResult validateAndSave(D draft);

    /** Delete the given id from the store and save. */
    protected abstract ValidationResult delete(String id);

    /** Reload records from disk. Called in show() and after any mutation. */
    protected abstract void reloadRecords() throws IOException;

    /** True if this draft represents a brand-new record (not yet in the store). */
    protected abstract boolean isNewDraft(D draft);

    // =========================================================================
    // Chrome construction
    // =========================================================================

    private void buildChrome() {
        root.defaults().pad(PAD);

        // ── Toolbar row ────────────────────────────────────────────────────────
        Table toolbar = new Table(skin);
        toolbar.setBackground(skin.getDrawable("battle-header"));
        toolbar.pad(10f);
        toolbar.defaults().pad(4f).fillY();

        Label titleLabel = new Label(title(), skin, "title");
        toolbar.add(titleLabel).left().expandX();

        TextButton newBtn  = new TextButton("NEW", skin, "primary");
        TextButton dupBtn  = new TextButton("COPY", skin);
        TextButton delBtn  = new TextButton("DELETE", skin);
        TextButton backBtn = new TextButton("BACK", skin);
        toolbar.add(newBtn, dupBtn, delBtn, backBtn).right();

        newBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.audio().play(SoundCue.UI_CONFIRM);
                startNew();
            }
        });
        dupBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.audio().play(selectedIndex < 0 ? SoundCue.UI_DENIED : SoundCue.UI_CONFIRM);
                duplicateCurrent();
            }
        });
        delBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.audio().play(selectedIndex < 0 ? SoundCue.UI_DENIED : SoundCue.UI_CONFIRM);
                deleteCurrent();
            }
        });
        backBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.audio().play(SoundCue.UI_BACK);
                leaveEditor();
            }
        });

        root.add(toolbar).growX().row();

        // ── Body: master list + detail ─────────────────────────────────────────
        Table body = new Table(skin);

        // Left: search + scrollable list
        Table left = new Table(skin);
        left.setBackground(skin.getDrawable("battle-palette"));
        left.pad(10f);
        // Left column sits on the dark-blue battle-palette: use the small-white
        // style so the periwinkle tint multiplies up correctly (a TEXT_DARK base
        // would render dark navy against the palette).
        Label libraryLabel = new Label("RECORDS", skin, "small-white");
        libraryLabel.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        left.add(libraryLabel).left().growX().padBottom(6f).row();
        searchField = new HoverTextField("", skin);
        searchField.setMessageText("search...");
        searchField.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) { refreshMasterList(); }
        });
        left.add(searchField).growX().padBottom(PAD).row();

        configureMasterRecordList(masterList);
        masterListContent.add(masterList).growX().top();
        masterScroll = new AxisLockedScrollPane(masterListContent, skin);
        masterScroll.setFadeScrollBars(false);
        masterScroll.setScrollingDisabled(true, false);

        Stack masterStack = new Stack();
        masterStack.add(masterScroll);
        masterStack.add(stickySectionHeaders);
        left.add(masterStack).grow();

        masterColumn = body.add(left).width(Gdx.graphics.getWidth() * LIST_W_FRAC).growY();

        // Right: scrollable detail form container. The pane itself is a navy
        // palette; each form section renders as a parchment card on top of it,
        // mirroring the character-select master/detail look.
        detailContainer = new Container<>();
        detailContainer.fill(true, true);
        ScrollPane.ScrollPaneStyle detailScrollStyle =
            new ScrollPane.ScrollPaneStyle(skin.get(ScrollPane.ScrollPaneStyle.class));
        detailScrollStyle.background = null;
        ScrollPane detailScroll = new AxisLockedScrollPane(detailContainer, detailScrollStyle);
        detailScroll.setFadeScrollBars(false);
        detailScroll.setScrollingDisabled(true, false);
        Table detail = new Table(skin);
        detail.setBackground(skin.getDrawable("battle-palette"));
        detail.pad(10f);
        detail.add(detailScroll).grow();
        body.add(detail).grow().padLeft(PAD);

        root.add(body).grow().row();

        // ── Action bar ─────────────────────────────────────────────────────────
        Table actionBar = new Table(skin);
        actionBar.setBackground(skin.getDrawable("battle-header"));
        actionBar.pad(8f);

        // The action bar sits on the dark-blue battle-header, so the dirty /
        // status labels use the small-white style (white base colour) — Label
        // actor colour multiplies the style fontColour, so recolouring a
        // TEXT_DARK "small" label to white still renders as dark navy.
        dirtyLabel = new Label("", skin, "small-white");
        actionBar.add(dirtyLabel).left().padRight(PAD);

        saveButton = new TextButton("SAVE", skin, "primary");
        saveButton.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                save();
            }
        });
        cancelButton = new TextButton("CANCEL", skin);
        cancelButton.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) {
                game.audio().play(SoundCue.UI_BACK);
                revert();
            }
        });
        actionBar.add(saveButton, cancelButton).left().padRight(PAD);

        statusLabel = new Label("", skin, "small-white");
        actionBar.add(statusLabel).expandX().left();

        root.add(actionBar).growX().padTop(PAD);
    }

    private void wireInput() {
        wireMasterRecordList(masterList);
        // Arrow-key navigation of the list (with focus), plus global hotkeys.
        //
        // Registered as a CAPTURE listener so it runs before LibGDX's stock
        // List keyboard handler (which also moves selection on UP/DOWN/HOME/END
        // when the list has keyboard focus). Without the capture phase + cancel,
        // both handlers fire on a single keypress and the selection jumps by 2.
        // We keep our own nudgeSelection because it wraps around and handles the
        // no-selection (selectedIndex < 0) case, then cancel() the event so the
        // stock List handler never sees it.
        stage.addCaptureListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int keycode) {
                Dialog dialog = topmostDialog();
                if (dialog != null) {
                    if (keycode == Input.Keys.ESCAPE) {
                        game.audio().play(SoundCue.UI_BACK);
                        dialog.hide();
                        event.cancel();
                        return true;
                    }
                    // Let the modal's controls receive input, but do not run
                    // editor-level navigation or save shortcuts behind it.
                    return false;
                }
                Actor keyboardFocus = stage.getKeyboardFocus();
                if (keycode == Input.Keys.ESCAPE
                    && keyboardFocus instanceof KeywordAutocompleteField keywordField
                    && keywordField.dismissSuggestions()) {
                    event.cancel();
                    return true;
                }
                if (keycode == Input.Keys.ESCAPE) {
                    game.audio().play(SoundCue.UI_BACK);
                    leaveEditor();
                    event.cancel();
                    return true;
                }
                if (keyboardFocus != null && !masterRecordLists.contains(keyboardFocus)) return false;
                if (keycode == Input.Keys.UP || keycode == Input.Keys.DOWN) {
                    // Desktop backends may emit keyDown repeatedly while a key is held.
                    // Drive repetition from render() instead so the cadence is consistent.
                    if (keycode == heldRecordKey) {
                        event.cancel();
                        return true;
                    }
                    int direction = keycode == Input.Keys.UP ? -1 : 1;
                    if (nudgeSelection(direction)) game.audio().play(SoundCue.UI_NAVIGATE);
                    heldRecordKey = keycode;
                    recordKeyRepeatTimer = RECORD_KEY_REPEAT_DELAY;
                    event.cancel();
                    return true;
                }
                if (keycode == Input.Keys.HOME || keycode == Input.Keys.END) {
                    int current = selectedVisibleRecordIndex();
                    int target = keycode == Input.Keys.HOME ? 0 : visibleRecordIds.size() - 1;
                    if (target >= 0 && target != current) {
                        suppressRecordSelectionSound = true;
                        try {
                            selectVisibleRecordIndex(target);
                        } finally {
                            suppressRecordSelectionSound = false;
                        }
                        int selected = selectedVisibleRecordIndex();
                        if (selected >= 0) scrollMasterListTo(selected);
                        if (selected != current) game.audio().play(SoundCue.UI_NAVIGATE);
                    }
                    event.cancel();
                    return true;
                }
                if (keycode == Input.Keys.S && Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)) {
                    save();
                    event.cancel();
                    return true;
                }
                return false;
            }

            @Override public boolean keyUp(InputEvent event, int keycode) {
                if (keycode != heldRecordKey) return false;
                stopRecordKeyRepeat();
                event.cancel();
                return true;
            }

            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                // LibGDX does not clear keyboard focus when you click outside a
                // TextField, so a focused numeric field (e.g. StatField) would
                // never get its focus-lost commit. Drop focus when the click
                // didn't land on a TextField (and no modal is open); the field's
                // FocusListener then runs and commits its value.
                if (topmostDialog() != null) return false;
                Actor target = stage.hit(x, y, true);
                Actor keyboardFocus = stage.getKeyboardFocus();
                if (target instanceof TextField
                    || (keyboardFocus instanceof KeywordAutocompleteField keywordField
                        && keywordField.ownsSuggestionActor(target))) {
                    return false;
                }
                if (stage.getKeyboardFocus() != null) stage.setKeyboardFocus(null);
                return false;
            }
        });
    }

    /** Advances a held Up/Down key after its initial delay. */
    private void repeatHeldRecordKey(float delta) {
        if (heldRecordKey == -1) return;
        Actor keyboardFocus = stage.getKeyboardFocus();
        if (topmostDialog() != null
            || (keyboardFocus != null && !masterRecordLists.contains(keyboardFocus))) {
            stopRecordKeyRepeat();
            return;
        }

        recordKeyRepeatTimer -= delta;
        while (recordKeyRepeatTimer <= 0f) {
            int direction = heldRecordKey == Input.Keys.UP ? -1 : 1;
            if (!nudgeSelection(direction)) {
                stopRecordKeyRepeat();
                return;
            }
            // Repeated navigation stays silent so a long hold does not layer UI sounds.
            recordKeyRepeatTimer += RECORD_KEY_REPEAT_INTERVAL;
        }
    }

    private void stopRecordKeyRepeat() {
        heldRecordKey = -1;
        recordKeyRepeatTimer = 0f;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public void show() {
        stopRecordKeyRepeat();
        removeDialogs();
        Gdx.input.setInputProcessor(stage);
        try {
            reloadRecords();
            selectedIndex = -1;
            draft = null;
            refreshMasterList();
            rebuildDetail();
            updateActionState();
            setStatus("", false);
        } catch (IOException e) {
            setStatus("Load failed: " + e.getMessage(), true);
        }
    }

    @Override
    public void render(float delta) {
        // #CDDCFA — light blue, shared across all screens
        Gdx.gl.glClearColor(0.804f, 0.863f, 0.980f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        repeatHeldRecordKey(delta);
        stage.act(delta);
        updateStickySectionHeaders();
        stage.draw();
    }

    @Override
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
        if (masterColumn != null) {
            masterColumn.width(width * LIST_W_FRAC);
            root.invalidateHierarchy();
        }
    }

    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   { stopRecordKeyRepeat(); removeDialogs(); }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        stage.dispose();
    }

    private Dialog topmostDialog() {
        for (int i = stage.getActors().size - 1; i >= 0; i--) {
            Actor actor = stage.getActors().get(i);
            if (actor instanceof Dialog dialog && dialog.isVisible()) return dialog;
        }
        return null;
    }

    private void removeDialogs() {
        for (int i = stage.getActors().size - 1; i >= 0; i--) {
            Actor actor = stage.getActors().get(i);
            if (actor instanceof Dialog) actor.remove();
        }
    }

    private void leaveEditor() {
        if (dirty) {
            confirmDiscard(game::showMainMenu);
        } else {
            game.showMainMenu();
        }
    }

    // =========================================================================
    // Master list
    // =========================================================================

    /**
     * Rebuild the master list from {@link #records}, honouring the search box.
     *
     * Items are sorted alphabetically by their display label within each section
     * after applying an optional search query.
     */
    protected void refreshMasterList() {
        String q = searchField.getText().trim().toLowerCase();
        List<D> visibleRecords = new ArrayList<>();
        for (D r : records) {
            String label = listLabel(r);
            if (q.isEmpty() || label.toLowerCase().contains(q)) {
                visibleRecords.add(r);
            }
        }
        visibleRecords.sort((a, b) ->
            String.CASE_INSENSITIVE_ORDER.compare(listLabel(a), listLabel(b)));

        String selectedId = selectedIndex >= 0 && selectedIndex < records.size()
            ? idOf(records.get(selectedIndex)) : null;
        suppressMasterListEvents = true;
        try {
            visibleRecordIds.clear();
            masterRecordLists.clear();
            recordIdsByList.clear();
            masterSectionViews.clear();
            masterListContent.clearChildren();

            List<String> sections = recordSections();
            if (sections.isEmpty()) {
                List<String> ids = visibleRecords.stream().map(this::idOf).toList();
                masterList.setItems(visibleRecords.stream()
                    .map(this::listLabel).toArray(String[]::new));
                masterListContent.add(masterList).growX().top();
                masterRecordLists.add(masterList);
                recordIdsByList.put(masterList, ids);
                visibleRecordIds.addAll(ids);
            } else {
                Map<String, List<D>> recordsBySection = new LinkedHashMap<>();
                for (String section : sections) {
                    recordsBySection.put(section, new ArrayList<>());
                    collapsedRecordSections.putIfAbsent(section, false);
                }
                for (D record : visibleRecords) {
                    List<D> sectionRecords = recordsBySection.get(recordSection(record));
                    if (sectionRecords != null) sectionRecords.add(record);
                }

                for (String section : sections) {
                    if (isRecordSectionHiddenByCollapsedAncestor(section)) continue;
                    Table header = createRecordSectionHeader(section);
                    masterListContent.add(header).growX().row();
                    masterSectionViews.add(new MasterSectionView(section, header));

                    if (Boolean.TRUE.equals(collapsedRecordSections.get(section))) continue;
                    List<D> sectionRecords = recordsBySection.get(section);
                    if (sectionRecords.isEmpty()) continue;
                    List<String> ids = sectionRecords.stream().map(this::idOf).toList();
                    HoverList<String> list = new HoverList<>(skin);
                    configureMasterRecordList(list);
                    wireMasterRecordList(list);
                    list.setItems(sectionRecords.stream()
                        .map(this::listLabel).toArray(String[]::new));
                    // Records align with their Attack / Defense / Utility header
                    // instead of acquiring an additional indentation level.
                    masterListContent.add(list).growX().top()
                        .padLeft(recordSectionIndent(section)).row();
                    masterRecordLists.add(list);
                    recordIdsByList.put(list, ids);
                    visibleRecordIds.addAll(ids);
                }
            }
            selectVisibleRecord(selectedId);
        } finally {
            suppressMasterListEvents = false;
        }
        renderedStickySections = List.of();
        masterListContent.invalidateHierarchy();
    }

    /** Move the master-list selection by delta, scrolling as needed. */
    private boolean nudgeSelection(int delta) {
        int itemCount = visibleRecordIds.size();
        if (itemCount == 0) return false;
        int current = selectedVisibleRecordIndex();
        int idx = current < 0 ? (delta > 0 ? 0 : itemCount - 1)
                              : Math.floorMod(current + delta, itemCount);
        suppressRecordSelectionSound = true;
        try {
            selectVisibleRecordIndex(idx);
        } finally {
            suppressRecordSelectionSound = false;
        }
        int selected = selectedVisibleRecordIndex();
        if (selected >= 0) scrollMasterListTo(selected);
        return selected != current;
    }

    /** Keeps keyboard-selected records visible within the master-list scroll pane. */
    private void scrollMasterListTo(int index) {
        if (index < 0 || index >= visibleRecordIds.size()) return;
        String id = visibleRecordIds.get(index);
        HoverList<String> target = null;
        int localIndex = -1;
        for (HoverList<String> list : masterRecordLists) {
            int found = recordIdsByList.getOrDefault(list, List.of()).indexOf(id);
            if (found >= 0) {
                target = list;
                localIndex = found;
                break;
            }
        }
        if (target == null) return;

        masterScroll.validate();
        masterListContent.validate();
        float itemHeight = target.getItemHeight();
        if (itemHeight <= 0f) return;
        float y = target.getHeight() - (localIndex + 1) * itemHeight;
        Vector2 position = target.localToAscendantCoordinates(
            masterListContent, new Vector2(0f, y));
        masterScroll.scrollTo(0f, position.y, target.getWidth(), itemHeight);
        masterScroll.updateVisualScroll();

        float rowTopFromContentTop = masterListContent.getHeight()
            - position.y - itemHeight;
        // ScrollPane does not know about the pinned overlay. If it aligned a row
        // with the physical top, move it back below the accumulated headers.
        for (int attempt = 0; attempt < 2; attempt++) {
            float scrollY = masterScroll.getScrollY();
            float stickyHeight = stickySectionHeightAt(scrollY);
            if (rowTopFromContentTop >= scrollY + stickyHeight) break;
            masterScroll.setScrollY(Math.max(0f, rowTopFromContentTop - stickyHeight));
        }
        masterScroll.updateVisualScroll();
    }

    private void configureMasterRecordList(HoverList<String> list) {
        list.getSelection().setRequired(false);
        list.getSelection().setMultiple(false);
    }

    /** Resolve list-local selections through record IDs so duplicate labels stay safe. */
    private void wireMasterRecordList(HoverList<String> list) {
        list.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (suppressMasterListEvents) return;
                int pickedIndex = list.getSelectedIndex();
                List<String> ids = recordIdsByList.getOrDefault(list, List.of());
                if (pickedIndex < 0 || pickedIndex >= ids.size()) return;

                suppressMasterListEvents = true;
                try {
                    for (HoverList<String> other : masterRecordLists) {
                        if (other != list) other.getSelection().clear();
                    }
                } finally {
                    suppressMasterListEvents = false;
                }

                String pickedId = ids.get(pickedIndex);
                for (int i = 0; i < records.size(); i++) {
                    if (Objects.equals(idOf(records.get(i)), pickedId)) {
                        selectRecord(i);
                        break;
                    }
                }
            }
        });
    }

    private int selectedVisibleRecordIndex() {
        for (HoverList<String> list : masterRecordLists) {
            int localIndex = list.getSelectedIndex();
            List<String> ids = recordIdsByList.getOrDefault(list, List.of());
            if (localIndex >= 0 && localIndex < ids.size()) {
                return visibleRecordIds.indexOf(ids.get(localIndex));
            }
        }
        return -1;
    }

    private void selectVisibleRecordIndex(int index) {
        if (index < 0 || index >= visibleRecordIds.size()) return;
        String id = visibleRecordIds.get(index);
        suppressMasterListEvents = true;
        HoverList<String> target = null;
        int localIndex = -1;
        try {
            for (HoverList<String> list : masterRecordLists) {
                List<String> ids = recordIdsByList.getOrDefault(list, List.of());
                int found = ids.indexOf(id);
                if (found >= 0) {
                    target = list;
                    localIndex = found;
                }
                list.getSelection().clear();
            }
        } finally {
            suppressMasterListEvents = false;
        }
        if (target != null) target.setSelectedIndex(localIndex);
    }

    private void selectVisibleRecord(String id) {
        boolean wasSuppressed = suppressMasterListEvents;
        suppressMasterListEvents = true;
        try {
            for (HoverList<String> list : masterRecordLists) {
                List<String> ids = recordIdsByList.getOrDefault(list, List.of());
                int localIndex = id == null ? -1 : ids.indexOf(id);
                if (localIndex >= 0) list.setSelectedIndex(localIndex);
                else list.getSelection().clear();
            }
        } finally {
            suppressMasterListEvents = wasSuppressed;
        }
    }

    private Table createRecordSectionHeader(String section) {
        boolean headSection = recordSectionParent(section) == null;
        Table header = new Table(skin);
        if (headSection) {
            header.setBackground(skin.getDrawable("battle-header"));
            header.pad(RECORD_SECTION_HEAD_VERTICAL_PADDING, 10f,
                RECORD_SECTION_HEAD_VERTICAL_PADDING, 10f);
        } else {
            header.setBackground(skin.getDrawable("battle-header"));
            header.pad(RECORD_SUBSECTION_VERTICAL_PADDING, recordSectionIndent(section),
                RECORD_SUBSECTION_VERTICAL_PADDING, 8f);
        }
        // The header itself remains touchable so clicks cannot reach rows hidden
        // beneath a pinned copy. Route wheel input to its sibling ScrollPane.
        header.setTouchable(Touchable.enabled);
        header.addListener(new InputListener() {
            @Override public boolean scrolled(
                InputEvent event, float x, float y, float amountX, float amountY
            ) {
                masterScroll.scrollBy(amountX, amountY);
                event.cancel();
                return true;
            }
        });

        Label title = new Label(recordSectionLabel(section), skin,
            "white");
        title.setColor(Color.WHITE);
        if (headSection) {
            title.setFontScale(RECORD_SECTION_HEAD_TITLE_SCALE);
        }
        title.setTouchable(Touchable.disabled);
        header.add(title).left().growX();

        boolean collapsed = Boolean.TRUE.equals(collapsedRecordSections.get(section));
        TextButton collapseButton = new TextButton(collapsed ? "+" : "-", skin);
        collapseButton.addListener(new ClickListener() {
            @Override public void clicked(InputEvent event, float x, float y) {
                game.audio().play(SoundCue.UI_CONFIRM);
                toggleRecordSection(section, !collapsed);
            }
        });
        if (headSection) {
            collapseButton.getLabel().setFontScale(RECORD_SECTION_HEAD_TITLE_SCALE);
            if (windowsLayout) header.add(collapseButton).right().size(54f, 49.5f);
            else header.add(collapseButton).right().size(36f, 33f);
        } else {
            if (windowsLayout) header.add(collapseButton).right().size(36f, 33f);
            else header.add(collapseButton).right().size(24f, 22f);
        }
        return header;
    }

    private float recordSectionIndent(String section) {
        int depth = 0;
        String ancestor = recordSectionParent(section);
        while (ancestor != null) {
            depth++;
            ancestor = recordSectionParent(ancestor);
        }
        return depth * RECORD_SUBSECTION_INDENT;
    }

    /** True when any ancestor of {@code section} is minimized. */
    private boolean isRecordSectionHiddenByCollapsedAncestor(String section) {
        String ancestor = recordSectionParent(section);
        while (ancestor != null) {
            if (Boolean.TRUE.equals(collapsedRecordSections.get(ancestor))) return true;
            ancestor = recordSectionParent(ancestor);
        }
        return false;
    }

    /** Accumulates headers at the top once their original rows scroll past. */
    private void updateStickySectionHeaders() {
        if (masterSectionViews.isEmpty()) {
            if (!renderedStickySections.isEmpty()) stickySectionHeaders.clearChildren();
            renderedStickySections = List.of();
            return;
        }

        masterScroll.validate();
        masterListContent.validate();
        Drawable scrollBackground = masterScroll.getStyle().background;
        float leftInset = scrollBackground == null ? 0f : scrollBackground.getLeftWidth();
        float rightInset = scrollBackground == null ? 0f : scrollBackground.getRightWidth();
        float topInset = scrollBackground == null ? 0f : scrollBackground.getTopHeight();
        if (masterScroll.isScrollY()) rightInset += masterScroll.getScrollBarWidth();
        if (stickySectionHeaders.getPadLeft() != leftInset
            || stickySectionHeaders.getPadRight() != rightInset
            || stickySectionHeaders.getPadTop() != topInset) {
            stickySectionHeaders.padLeft(leftInset).padRight(rightInset).padTop(topInset);
        }
        float scrollY = masterScroll.getVisualScrollY();
        float stackedHeight = 0f;
        List<String> sticky = new ArrayList<>();
        for (MasterSectionView section : masterSectionViews) {
            Table header = section.header();
            float topFromContentTop = masterListContent.getHeight()
                - header.getY() - header.getHeight();
            if (topFromContentTop - scrollY <= stackedHeight + 0.5f) {
                sticky.add(section.name());
                stackedHeight += header.getHeight();
            }
        }
        if (sticky.equals(renderedStickySections)) return;

        renderedStickySections = List.copyOf(sticky);
        stickySectionHeaders.clearChildren();
        for (String section : sticky) {
            stickySectionHeaders.add(createRecordSectionHeader(section)).growX().row();
        }
    }

    private float stickySectionHeightAt(float scrollY) {
        float stackedHeight = 0f;
        for (MasterSectionView section : masterSectionViews) {
            Table header = section.header();
            float topFromContentTop = masterListContent.getHeight()
                - header.getY() - header.getHeight();
            if (topFromContentTop - scrollY <= stackedHeight + 0.5f) {
                stackedHeight += header.getHeight();
            }
        }
        return stackedHeight;
    }

    private void toggleRecordSection(String section, boolean collapsed) {
        float scrollY = masterScroll.getScrollY();
        collapsedRecordSections.put(section, collapsed);
        refreshMasterList();
        masterScroll.invalidateHierarchy();
        masterScroll.validate();
        masterScroll.setScrollY(Math.min(scrollY, masterScroll.getMaxY()));
        masterScroll.updateVisualScroll();
        if (!collapsed && selectedIndex >= 0 && selectedIndex < records.size()) {
            int visibleIndex = visibleRecordIds.indexOf(idOf(records.get(selectedIndex)));
            if (visibleIndex >= 0) scrollMasterListTo(visibleIndex);
        }
    }

    private record MasterSectionView(String name, Table header) {}

    // =========================================================================
    // Selection / draft management
    // =========================================================================

    /** Load record at idx into a fresh draft and rebuild the detail form. */
    protected void selectRecord(int idx) {
        if (idx < 0 || idx >= records.size()) return;
        if (dirty) {
            String currentId = selectedIndex >= 0 && selectedIndex < records.size()
                ? idOf(records.get(selectedIndex)) : null;
            selectVisibleRecord(currentId);
            if (!suppressRecordSelectionSound) game.audio().play(SoundCue.UI_CONFIRM);
            confirmDiscard(() -> doSelect(idx, false));
            return;
        }
        doSelect(idx, !suppressRecordSelectionSound);
    }

    private void doSelect(int idx) {
        doSelect(idx, false);
    }

    private void doSelect(int idx, boolean playSound) {
        selectedIndex = idx;
        String selectedId = idOf(records.get(idx));
        selectVisibleRecord(selectedId);
        draft = draftFromRecord(records.get(idx));
        suppressDirty = true;
        rebuildDetail();
        suppressDirty = false;
        clearDirty();
        setStatus("", false);
        int visibleIndex = visibleRecordIds.indexOf(selectedId);
        if (visibleIndex >= 0) scrollMasterListTo(visibleIndex);
        if (playSound) game.audio().play(SoundCue.UI_CONFIRM);
    }

    /** Begin editing a brand-new record. */
    protected void startNew() {
        if (dirty && !confirmDiscard(this::doStartNew)) return;
        doStartNew();
    }

    private void doStartNew() {
        draft = newDraft();
        stampNewId(draft);
        selectedIndex = -1;
        selectVisibleRecord(null);
        suppressDirty = true;
        rebuildDetail();
        suppressDirty = false;
        clearDirty();
        setStatus("Editing new record — fill in fields and click SAVE.", false);
    }

    /** Duplicate the currently selected record into a new draft. */
    protected void duplicateCurrent() {
        if (selectedIndex < 0) { setStatus("Select a record to copy first.", true); return; }
        D stored = records.get(selectedIndex);
        D copy = draftFromRecord(stored);
        draft = copy;
        // A copy is treated as a brand-new record: it gets the next id, not the
        // source's id. The repo re-assigns on add anyway, but stamping now lets
        // engine validation pass and lets the form show the prospective id.
        stampNewId(draft);
        selectedIndex = -1;
        selectVisibleRecord(null);
        suppressDirty = true;
        rebuildDetail();
        suppressDirty = false;
        markDirty(); // a duplicate is always "new" / dirty
        setStatus("Editing copy — SAVE to add as a new record.", false);
    }

    /**
     * Assign the prospective next id to a draft that is about to be created.
     * Subclasses override to write the id onto their DTO; the default does
     * nothing. The repo will reassign on add, but the draft needs a non-blank
     * id so engine validation (Entity / Move.Builder) succeeds beforehand.
     */
    protected void stampNewId(D draft) { /* override in subclass */ }

    /** Delete the currently selected record (with confirmation). */
    protected void deleteCurrent() {
        if (selectedIndex < 0) { setStatus("Select a record to delete first.", true); return; }
        D stored = records.get(selectedIndex);
        String id = idOf(stored);
        confirmDelete(listLabel(stored), () -> {
            ValidationResult r = delete(id);
            if (r.isOk()) {
                game.audio().play(SoundCue.UI_DELETE);
                try {
                    reloadRecords();
                    selectedIndex = -1;
                    draft = null;
                    refreshMasterList();
                    rebuildDetail();
                    clearDirty();
                    setStatus("Deleted.", false);
                } catch (IOException e) {
                    setStatus("Reload after delete failed: " + e.getMessage(), true);
                }
            } else {
                game.audio().play(SoundCue.UI_DENIED);
                setStatus(r.getMessage(), true);
            }
        });
    }

    // =========================================================================
    // Detail form
    // =========================================================================

    /** Tear down + rebuild the detail form for the current draft. */
    protected void rebuildDetail() {
        if (draft == null) {
            detailContainer.setActor(emptyDetail());
            return;
        }
        Actor form = buildDetailForm(draft);
        detailContainer.setActor(form);
    }

    private Actor emptyDetail() {
        Table t = new Table(skin);
        // Detail pane sits on the dark-blue battle-palette: small-white base so
        // the periwinkle tint reads correctly (TEXT_DARK base multiplies to navy).
        Label l = new Label("No record selected.\nClick NEW, or select a record from the list.",
            skin, "small-white");
        l.setAlignment(Align.center);
        // Light periwinkle: readable over the navy palette background.
        l.setColor(new Color(0.720f, 0.800f, 0.950f, 1f));
        t.add(l).expand().center();
        return t;
    }

    // =========================================================================
    // Save / revert
    // =========================================================================

    protected void save() {
        if (draft == null) {
            game.audio().play(SoundCue.UI_DENIED);
            return;
        }
        ValidationResult r;
        try {
            r = validateAndSave(draft);
        } catch (Exception ex) {
            r = ValidationResult.error("Save failed: " + ex.getMessage());
        }
        if (r.isOk()) {
            try {
                // Clear dirty BEFORE reselecting so the programmatic
                // selecting the saved row below doesn't trip the
                // "discard changes?" guard in selectRecord() — we just saved,
                // so there is nothing to discard.
                clearDirty();
                reloadRecords();
                refreshMasterList();
                // Reselect the saved record (by name match for new records).
                String savedLabel = listLabel(draft);
                int savedIndex = -1;
                for (int i = 0; i < records.size(); i++) {
                    if (Objects.equals(idOf(records.get(i)), idOf(draft))
                        || Objects.equals(listLabel(records.get(i)), savedLabel)) {
                        selectedIndex = i;
                        savedIndex = i;
                        int visibleIndex = visibleRecordIds.indexOf(idOf(records.get(i)));
                        suppressRecordSelectionSound = true;
                        try {
                            if (visibleIndex >= 0) {
                                selectVisibleRecordIndex(visibleIndex);
                                scrollMasterListTo(visibleIndex);
                            }
                            else selectVisibleRecord(null);
                        } finally {
                            suppressRecordSelectionSound = false;
                        }
                        break;
                    }
                }
                draft = savedIndex >= 0
                    ? draftFromRecord(records.get(savedIndex))
                    : draftFromRecord(draft);
                suppressDirty = true;
                rebuildDetail();
                suppressDirty = false;
                setStatus(r.getMessage(), false);
                game.audio().play(SoundCue.UI_CONFIRM);
            } catch (IOException e) {
                setStatus("Saved but reload failed: " + e.getMessage(), true);
                game.audio().play(SoundCue.UI_CONFIRM);
            }
        } else {
            setStatus(r.getMessage(), true);
            game.audio().play(SoundCue.UI_DENIED);
        }
    }

    /** Discard the current draft: reload from the stored record, or clear if new. */
    protected void revert() {
        if (selectedIndex >= 0) {
            doSelect(selectedIndex);
            setStatus("Reverted.", false);
        } else {
            draft = null;
            rebuildDetail();
            clearDirty();
            setStatus("Cancelled new record.", false);
        }
    }

    // =========================================================================
    // Dirty tracking + status
    // =========================================================================

    /** Call from any field listener to mark the draft as changed. */
    protected void markDirty() {
        if (suppressDirty) return;
        dirty = true;
        updateActionState();
    }

    protected void clearDirty() {
        dirty = false;
        updateActionState();
    }

    private void updateActionState() {
        dirtyLabel.setText(dirty ? "* UNSAVED CHANGES" : "");
        saveButton.setDisabled(draft == null);
        cancelButton.setDisabled(draft == null);
    }

    protected void setStatus(String msg, boolean error) {
        statusLabel.setText(msg);
        // The status label lives in the dark-blue action bar, where the
        // text-error/text-ok colours (dark red / green) are hard to read.
        // Keep it white regardless of tone so messages stay legible.
        statusLabel.setColor(Color.WHITE);
    }

    // =========================================================================
    // Confirmation dialogs (simple inline; could be upgraded later)
    // =========================================================================

    /** Show a yes/no confirm; runs onConfirm if accepted. */
    protected void confirmDelete(String what, Runnable onConfirm) {
        com.badlogic.gdx.scenes.scene2d.ui.Dialog dlg =
            new com.badlogic.gdx.scenes.scene2d.ui.Dialog("Confirm Delete", skin) {
                @Override
                protected void result(Object object) {
                    if (Boolean.TRUE.equals(object)) {
                        onConfirm.run();
                    } else {
                        game.audio().play(SoundCue.UI_BACK);
                    }
                }
            };
        dlg.text("Delete \"" + what + "\"?\nThis cannot be undone.");
        dlg.button("Delete", true);
        dlg.button("Cancel", false);
        dlg.show(stage);
    }

    /** Confirm discarding unsaved changes before switching selection. */
    protected boolean confirmDiscard(Runnable onAccept) {
        com.badlogic.gdx.scenes.scene2d.ui.Dialog dlg =
            new com.badlogic.gdx.scenes.scene2d.ui.Dialog("Discard Changes?", skin) {
                @Override
                protected void result(Object object) {
                    if (Boolean.TRUE.equals(object)) {
                        game.audio().play(SoundCue.UI_CONFIRM);
                        onAccept.run();
                    } else {
                        game.audio().play(SoundCue.UI_BACK);
                    }
                }
            };
        dlg.text("You have unsaved changes.\nDiscard them?");
        dlg.button("Discard", true);
        dlg.button("Keep Editing", false);
        dlg.show(stage);
        return false; // we always defer; selection happens via onAccept
    }

    // =========================================================================
    // Form kit — shared section/row builders for the detail forms
    // =========================================================================

    /**
     * Root table for a detail form: a single top-aligned column of section
     * cards. Add sections with {@link #formSection(Table, String)}.
     */
    protected Table formRoot() {
        Table form = new Table(skin);
        form.top();
        form.defaults().growX().padBottom(10f);
        form.pad(2f);
        return form;
    }

    /**
     * Append a parchment section card with a navy title strip to the form.
     * Returns the section body table; add field rows to it.
     */
    protected Table formSection(Table form, String title) {
        Table card = new Table(skin);
        card.setBackground(skin.getDrawable("battle-card"));
        card.top();
        card.pad(10f);

        Table strip = new Table(skin);
        strip.setBackground(skin.getDrawable("battle-header"));
        strip.pad(6f, 10f, 6f, 10f);
        Label t = new Label(title, skin, "white");
        t.setColor(new Color(1f, 1f, 1f, 1f));
        strip.add(t).left().growX();
        card.add(strip).growX().padBottom(8f).row();

        Table body = new Table(skin);
        body.top().left();
        body.defaults().left().pad(3f);
        card.add(body).growX();

        form.add(card).growX();
        form.row();
        return body;
    }

    /** Muted helper text for use inside form sections. */
    protected Label formHint(String text) {
        Label l = new Label(text, skin, "small");
        l.setColor(skin.get("text-dim", Color.class));
        return l;
    }

    /** A row pairing an aligned label column with an arbitrary field actor. */
    protected Table labelledRow(String label, Actor field) {
        Table row = new Table(skin);
        addFormLabel(row, label);
        row.add(field).growX();
        return row;
    }

    /**
     * A non-interactive {@code #id} badge — small muted text shown at the top
     * of the IDENTITY section. Not clickable / not hover-highlighted.
     *
     * @param id the record id (e.g. "000007"), or null for an unsaved new record
     */
    protected Label idBadge(String id) {
        Label l = new Label("#" + (id == null ? "—" : id), skin, "small");
        l.setColor(skin.get("text-dim", Color.class));
        return l;
    }

    /**
     * A labelled text field row. The supplier reads the current value for
     * initial display; field edits call {@code onChange.accept(text)} and
     * {@link #markDirty()}.
     */
    protected Table labelledField(String label, String initial,
                                  java.util.function.Consumer<String> onChange) {
        Table row = new Table(skin);
        addFormLabel(row, label);
        TextField tf = new HoverTextField(initial == null ? "" : initial, skin);
        tf.setTextFieldFilter((TextField textField, char c) -> true);
        tf.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                onChange.accept(tf.getText());
                markDirty();
            }
        });
        row.add(tf).growX();
        return row;
    }

    /** A labelled text field that offers documented keywords for uppercase input. */
    protected Table labelledKeywordField(String label, String initial,
                                         java.util.function.Consumer<String> onChange) {
        Table row = new Table(skin);
        addFormLabel(row, label);
        TextField tf = new KeywordAutocompleteField(
            initial == null ? "" : initial, skin, uiProfile);
        tf.setTextFieldFilter((TextField textField, char c) -> true);
        tf.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                onChange.accept(tf.getText());
                markDirty();
            }
        });
        row.add(tf).growX();
        return row;
    }

    /**
     * A labelled integer text field with min/max clamping on edit. The field
     * is fixed-width — numeric entry does not need a full-width box.
     */
    protected Table labelledIntField(String label, int initial, int min, int max,
                                     java.util.function.IntConsumer onChange) {
        Table row = new Table(skin);
        addFormLabel(row, label);
        TextField tf = new HoverTextField(String.valueOf(initial), skin);
        tf.setTextFieldFilter((TextField textField, char c) ->
            Character.isDigit(c) || c == '-');
        tf.setTextFieldListener((TextField textField, char c) -> {
            if (c == '\n' || c == '\t') return; // handled on change
        });
        tf.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                String s = tf.getText().trim();
                if (s.isEmpty()) return;
                try {
                    int v = Integer.parseInt(s);
                    v = Math.max(min, Math.min(max, v));
                    onChange.accept(v);
                    markDirty();
                } catch (NumberFormatException ignored) { /* keep editing */ }
            }
        });
        row.add(tf).left().width(windowsLayout ? 180f : 120f);
        row.add().growX(); // spacer keeps the field left-anchored
        return row;
    }

    private void addFormLabel(Table row, String text) {
        Label label = new Label(text, skin);
        if (windowsLayout) {
            row.add(label).left().minWidth(0f).prefWidth(300f).maxWidth(300f).padRight(PAD);
        } else {
            row.add(label).left().minWidth(FORM_LABEL_WIDTH).padRight(PAD);
        }
    }
}
