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
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.HorizontalGroup;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.ui.HoverScrollStage;
import com.jjktbf.graphics.ui.pixel.HoverList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
    /** Minimum width of the label column in form rows, for cross-row alignment. */
    private static final float FORM_LABEL_WIDTH = 200f;

    // ── Injected deps ──────────────────────────────────────────────────────────

    protected final JJKGame     game;
    protected final AssetLoader assets;
    protected final Skin        skin;

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
    protected ScrollPane masterScroll;
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

    // =========================================================================
    // Construction
    // =========================================================================

    protected EditorScreenBase(JJKGame game, AssetLoader assets) {
        this.game   = game;
        this.assets = assets;
        this.skin   = assets.editorSkin;
        this.stage  = new HoverScrollStage(new ScreenViewport());

        this.root = new Table();
        this.root.setFillParent(true);
        this.root.pad(20f);
        this.stage.addActor(root);

        this.masterList = new HoverList<>(skin);
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
            @Override public void clicked(InputEvent e, float x, float y) { startNew(); }
        });
        dupBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { duplicateCurrent(); }
        });
        delBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { deleteCurrent(); }
        });
        backBtn.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { leaveEditor(); }
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

        masterList.getSelection().setRequired(false);
        masterList.getSelection().setMultiple(false);
        masterScroll = new AxisLockedScrollPane(masterList, skin);
        masterScroll.setFadeScrollBars(false);
        masterScroll.setScrollingDisabled(true, false);
        left.add(masterScroll).grow();

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
            @Override public void clicked(InputEvent e, float x, float y) { save(); }
        });
        cancelButton = new TextButton("CANCEL", skin);
        cancelButton.addListener(new ClickListener() {
            @Override public void clicked(InputEvent e, float x, float y) { revert(); }
        });
        actionBar.add(saveButton, cancelButton).left().padRight(PAD);

        statusLabel = new Label("", skin, "small-white");
        actionBar.add(statusLabel).expandX().left();

        root.add(actionBar).growX().padTop(PAD);
    }

    private void wireInput() {
        // Resolve the visible index through its parallel ID list because search
        // filtering/sorting and duplicate labels make raw record indices unsafe.
        masterList.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                int pickedIndex = masterList.getSelectedIndex();
                if (pickedIndex < 0 || pickedIndex >= visibleRecordIds.size()) return;
                String pickedId = visibleRecordIds.get(pickedIndex);
                for (int i = 0; i < records.size(); i++) {
                    if (Objects.equals(idOf(records.get(i)), pickedId)) {
                        selectRecord(i);
                        break;
                    }
                }
            }
        });

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
                        dialog.hide();
                        event.cancel();
                        return true;
                    }
                    // Let the modal's controls receive input, but do not run
                    // editor-level navigation or save shortcuts behind it.
                    return false;
                }
                if (keycode == Input.Keys.ESCAPE) { leaveEditor(); event.cancel(); return true; }
                if (stage.getKeyboardFocus() == searchField) return false;
                if (keycode == Input.Keys.UP)   { nudgeSelection(-1); event.cancel(); return true; }
                if (keycode == Input.Keys.DOWN) { nudgeSelection(+1); event.cancel(); return true; }
                if (keycode == Input.Keys.S && Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)) { save(); event.cancel(); return true; }
                return false;
            }

            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                // LibGDX does not clear keyboard focus when you click outside a
                // TextField, so a focused numeric field (e.g. StatField) would
                // never get its focus-lost commit. Drop focus when the click
                // didn't land on a TextField (and no modal is open); the field's
                // FocusListener then runs and commits its value.
                if (topmostDialog() != null) return false;
                Actor target = stage.hit(x, y, true);
                if (target instanceof TextField) return false;
                if (stage.getKeyboardFocus() != null) stage.setKeyboardFocus(null);
                return false;
            }
        });
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public void show() {
        removeDialogs();
        Gdx.input.setInputProcessor(stage);
        try {
            reloadRecords();
            refreshMasterList();
            selectedIndex = -1;
            draft = null;
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
        stage.act(delta);
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
    @Override public void hide()   { removeDialogs(); }

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
     * With no search query, items are shown in id order (records is loaded
     * sequentially from the repo, so id order == list order).
     * With a search query, the *filtered matches* are sorted alphabetically by
     * their display label — so typing narrows and re-orders for quick finding.
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
        // Searching re-orders matches alphabetically for quick scanning;
        // no-query view keeps id order (which == records order).
        if (!q.isEmpty()) {
            visibleRecords.sort((a, b) ->
                String.CASE_INSENSITIVE_ORDER.compare(listLabel(a), listLabel(b)));
        }
        visibleRecordIds.clear();
        visibleRecords.forEach(record -> visibleRecordIds.add(idOf(record)));
        masterList.setItems(visibleRecords.stream()
            .map(this::listLabel).toArray(String[]::new));
    }

    /** Move the master-list selection by delta, scrolling as needed. */
    private void nudgeSelection(int delta) {
        int itemCount = masterList.getItems().size;
        if (itemCount == 0) return;
        int current = masterList.getSelectedIndex();
        int idx = current < 0 ? (delta > 0 ? 0 : itemCount - 1)
                              : Math.floorMod(current + delta, itemCount);
        masterList.setSelectedIndex(idx);
    }

    // =========================================================================
    // Selection / draft management
    // =========================================================================

    /** Load record at idx into a fresh draft and rebuild the detail form. */
    protected void selectRecord(int idx) {
        if (idx < 0 || idx >= records.size()) return;
        if (dirty && !confirmDiscard(() -> doSelect(idx))) return;
        doSelect(idx);
    }

    private void doSelect(int idx) {
        selectedIndex = idx;
        draft = draftFromRecord(records.get(idx));
        suppressDirty = true;
        rebuildDetail();
        suppressDirty = false;
        clearDirty();
        setStatus("", false);
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
        masterList.getSelection().clear();
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
        masterList.getSelection().clear();
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
                try {
                    reloadRecords();
                    refreshMasterList();
                    selectedIndex = -1;
                    draft = null;
                    rebuildDetail();
                    clearDirty();
                    setStatus("Deleted.", false);
                } catch (IOException e) {
                    setStatus("Reload after delete failed: " + e.getMessage(), true);
                }
            } else {
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
        if (draft == null) return;
        ValidationResult r;
        try {
            r = validateAndSave(draft);
        } catch (Exception ex) {
            r = ValidationResult.error("Save failed: " + ex.getMessage());
        }
        if (r.isOk()) {
            try {
                // Clear dirty BEFORE reselecting so the programmatic
                // masterList.setSelectedIndex(i) below doesn't trip the
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
                        if (visibleIndex >= 0) masterList.setSelectedIndex(visibleIndex);
                        else masterList.getSelection().clear();
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
            } catch (IOException e) {
                setStatus("Saved but reload failed: " + e.getMessage(), true);
            }
        } else {
            setStatus(r.getMessage(), true);
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
                    if (Boolean.TRUE.equals(object)) onConfirm.run();
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
                    if (Boolean.TRUE.equals(object)) onAccept.run();
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
        row.add(new Label(label, skin)).left().minWidth(FORM_LABEL_WIDTH).padRight(PAD);
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
        row.add(new Label(label, skin)).left().minWidth(FORM_LABEL_WIDTH).padRight(PAD);
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

    /**
     * A labelled integer text field with min/max clamping on edit. The field
     * is fixed-width — numeric entry does not need a full-width box.
     */
    protected Table labelledIntField(String label, int initial, int min, int max,
                                     java.util.function.IntConsumer onChange) {
        Table row = new Table(skin);
        row.add(new Label(label, skin)).left().minWidth(FORM_LABEL_WIDTH).padRight(PAD);
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
        row.add(tf).left().width(120f);
        row.add().growX(); // spacer keeps the field left-anchored
        return row;
    }
}
