package com.jjktbf.graphics.ui.pixel;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.jjktbf.graphics.AssetLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a complete Scene2D {@link Skin} entirely in code, drawing every UI
 * texture as a 9-patch pixel pixmap at startup (no binary asset files required).
 *
 * The visual language matches the battle planner and character dossier:
 *   - Ink-blue framed headers and palette surfaces
 *   - Warm parchment cards with a yellow hover state
 *   - Green primary action buttons
 *   - Square, nearest-neighbour pixel details
 *
 * Usage:
 *   Skin skin = PixelSkin.create();
 *
 * Disposal:
 *   The skin owns Textures + Fonts. Call {@code skin.dispose()} when done — but
 *   because the editor font is generated here (not shared with AssetLoader's
 *   pixel fonts), disposing this skin does NOT affect the battle UI.
 */
public final class PixelSkin {

    // ── Battle UI palette ───────────────────────────────────────────────────────

    private static final Color HEADER_FILL    = new Color(0.110f, 0.145f, 0.235f, 1f);
    private static final Color HEADER_BORDER  = new Color(0.035f, 0.050f, 0.095f, 1f);
    private static final Color HEADER_LIGHT   = new Color(0.390f, 0.610f, 0.940f, 1f);
    private static final Color HEADER_SHADOW  = new Color(0.030f, 0.045f, 0.085f, 1f);
    private static final Color PALETTE_FILL   = new Color(0.150f, 0.185f, 0.280f, 1f);
    private static final Color PALETTE_BORDER = new Color(0.045f, 0.060f, 0.110f, 1f);
    private static final Color PALETTE_LIGHT  = new Color(0.360f, 0.450f, 0.660f, 1f);
    private static final Color PALETTE_SHADOW = new Color(0.025f, 0.035f, 0.070f, 1f);
    private static final Color PAPER          = new Color(0.965f, 0.949f, 0.890f, 1f);
    private static final Color INK            = new Color(0.055f, 0.075f, 0.125f, 1f);
    private static final Color TEXT_DARK      = new Color(0.090f, 0.125f, 0.205f, 1f);
    private static final Color TEXT_MUTED     = new Color(0.400f, 0.445f, 0.545f, 1f);
    private static final Color YELLOW         = new Color(1.000f, 0.835f, 0.180f, 1f);
    private static final Color CARD_HOVER     = new Color(1f, 0.985f, 0.840f, 1f);
    private static final Color PRIMARY         = new Color(0.200f, 0.630f, 0.390f, 1f);
    private static final Color PRIMARY_BORDER  = new Color(0.030f, 0.180f, 0.090f, 1f);
    private static final Color PRIMARY_LIGHT   = new Color(0.510f, 0.900f, 0.610f, 1f);
    private static final Color PRIMARY_SHADOW  = new Color(0.050f, 0.330f, 0.150f, 1f);
    private static final Color PRIMARY_HOVER   = new Color(0.300f, 0.760f, 0.440f, 1f);
    private static final Color SELECTION       = new Color(1.000f, 0.835f, 0.180f, 1f);

    /** Slider groove. */
    private static final Color SLIDER_BG     = new Color(0.070f, 0.085f, 0.135f, 1f);
    /** Slider knob face. */
    private static final Color SLIDER_KNOB   = PRIMARY;

    /** Scrollbar groove. */
    private static final Color SCROLL_BG     = new Color(0.250f, 0.290f, 0.390f, 1f);
    /** Scrollbar knob. */
    private static final Color SCROLL_KNOB   = new Color(0.380f, 0.420f, 0.560f, 1f);

    /** Bright yellow used for hover and selection feedback. */
    private static final Color TEXT_HOVER    = YELLOW;
    /** Red for error/validation feedback. */
    private static final Color TEXT_ERROR    = new Color(0.760f, 0.090f, 0.090f, 1f);
    /** Green for success / dirty-saved feedback. */
    private static final Color TEXT_OK       = PRIMARY;
    /** Amber for dirty-indicator. */
    private static final Color TEXT_DIRTY    = new Color(0.760f, 0.490f, 0.090f, 1f);

    /** Fonts generated and added to the skin. */
    private final BitmapFont font;
    private final BitmapFont fontSmall;
    private final BitmapFont fontLarge;

    /** Tracks every Texture we create so dispose() can free GPU memory. */
    private final List<Texture> ownedTextures = new ArrayList<>();
    private final List<BitmapFont> ownedFonts = new ArrayList<>();

    // ── Factory ─────────────────────────────────────────────────────────────────

    /**
     * Build a fresh, self-contained Skin with all editor styles registered.
     * The caller is responsible for disposing the returned skin.
     */
    public static Skin create() {
        PixelSkin builder = new PixelSkin();
        return builder.build();
    }

    private PixelSkin() {
        // Generate our own editor fonts from the bundled TTF so disposing this
        // skin never touches AssetLoader's battle fonts.
        BitmapFont[] fonts = generateFonts();
        this.font      = fonts[0];
        this.fontSmall = fonts[1];
        this.fontLarge = fonts[2];
        ownedFonts.add(font);
        ownedFonts.add(fontSmall);
        ownedFonts.add(fontLarge);
    }

    // =========================================================================
    // Build
    // =========================================================================

    private Skin build() {
        Skin skin = new Skin();

        skin.add("default", font, BitmapFont.class);
        skin.add("small",   fontSmall, BitmapFont.class);
        skin.add("large",   fontLarge, BitmapFont.class);

        addColors(skin);

        // These frames intentionally use the same stepped-corner construction as
        // BattleUiAssets, keeping Scene2D controls visually aligned with the
        // hand-drawn battle and dossier screens.
        Drawable header       = battleFrame("battle-header", HEADER_FILL, HEADER_BORDER, HEADER_LIGHT, HEADER_SHADOW);
        Drawable palette      = battleFrame("battle-palette", PALETTE_FILL, PALETTE_BORDER, PALETTE_LIGHT, PALETTE_SHADOW);
        Drawable card         = battleFrame("battle-card", PAPER, new Color(0.105f, 0.135f, 0.205f, 1f),
            Color.WHITE, new Color(0.700f, 0.680f, 0.590f, 1f));
        Drawable cardOver     = battleFrame("battle-card-over", CARD_HOVER, new Color(0.965f, 0.670f, 0.120f, 1f),
            new Color(1f, 1f, 0.780f, 1f), new Color(0.720f, 0.420f, 0.080f, 1f));
        Drawable cardDisabled = battleFrame("battle-card-disabled", new Color(0.660f, 0.670f, 0.700f, 1f),
            new Color(0.300f, 0.320f, 0.380f, 1f), new Color(0.800f, 0.810f, 0.840f, 1f),
            new Color(0.440f, 0.450f, 0.500f, 1f));
        Drawable primary      = battleFrame("battle-primary", PRIMARY, PRIMARY_BORDER, PRIMARY_LIGHT, PRIMARY_SHADOW);
        Drawable primaryOver  = battleFrame("battle-primary-over", PRIMARY_HOVER, YELLOW,
            new Color(0.780f, 1f, 0.730f, 1f), new Color(0.100f, 0.430f, 0.180f, 1f));
        Drawable textfield    = battleFrame("textfield", new Color(1f, 1f, 0.980f, 1f),
            new Color(0.075f, 0.095f, 0.145f, 1f), Color.WHITE, new Color(0.630f, 0.650f, 0.720f, 1f));
        Drawable textfieldOver = battleFrame("textfield-over", new Color(1f, 1f, 0.980f, 1f), YELLOW,
            new Color(1f, 1f, 0.780f, 1f), new Color(0.720f, 0.420f, 0.080f, 1f));
        Drawable selection    = patch("sel", SELECTION);
        Drawable selectionRow = patch("sel-row", new Color(SELECTION.r, SELECTION.g, SELECTION.b, 0.35f));
        Drawable sliderH      = patch("slider-bg-h",SLIDER_BG);
        Drawable sliderKnobH  = patch("slider-knob",SLIDER_KNOB);
        Drawable checkboxOn   = flat("chk-on",      11, PRIMARY, PRIMARY_BORDER);
        Drawable checkboxOff  = flat("chk-off",     11, PAPER, INK);
        // Light-grey "locked-on" checkbox — signals a tag that's selected by
        // rule (not manual choice) and can't be toggled.
        Drawable checkboxLocked = flat("chk-locked", 11, new Color(0.78f, 0.78f, 0.82f, 1f), INK);
        Drawable scrollH      = patch("scroll-h",   SCROLL_BG);
        Drawable scrollV      = patch("scroll-v",   SCROLL_BG);
        Drawable scrollKnobH  = patch("scroll-knob-h", SCROLL_KNOB);
        Drawable scrollKnobV  = patch("scroll-knob-v", SCROLL_KNOB);
        Drawable listSel      = patch("list-sel",   new Color(SELECTION.r, SELECTION.g, SELECTION.b, 0.85f));
        // Pure-white dropdown + list background (was parchment, which let the
        // screen colour bleed through).
        Drawable whitePanel   = textfield;

        skin.add("panel",         card,         Drawable.class);
        skin.add("panel-inset",   card,         Drawable.class);
        skin.add("battle-header", header,       Drawable.class);
        skin.add("battle-palette", palette,     Drawable.class);
        skin.add("battle-card",   card,         Drawable.class);
        skin.add("battle-card-over", cardOver,  Drawable.class);
        skin.add("battle-card-disabled", cardDisabled, Drawable.class);
        skin.add("battle-primary", primary,     Drawable.class);
        skin.add("white-panel",   whitePanel,   Drawable.class);
        skin.add("button-up",     card,         Drawable.class);
        skin.add("button-down",   cardOver,     Drawable.class);
        skin.add("button-hover",  cardOver,     Drawable.class);
        skin.add("button-checked",cardOver,     Drawable.class);
        skin.add("textfield",     textfield,    Drawable.class);
        skin.add("textfield-over", textfieldOver, Drawable.class);
        skin.add("selection",     selection,    Drawable.class);
        skin.add("selection-row", selectionRow, Drawable.class);
        skin.add("slider-h",      sliderH,      Drawable.class);
        skin.add("slider-knob",   sliderKnobH,  Drawable.class);
        skin.add("check-on",      checkboxOn,   Drawable.class);
        skin.add("check-off",     checkboxOff,  Drawable.class);
        skin.add("check-locked",  checkboxLocked, Drawable.class);
        skin.add("scroll-h",      scrollH,      Drawable.class);
        skin.add("scroll-v",      scrollV,      Drawable.class);
        skin.add("scroll-knob-h", scrollKnobH,  Drawable.class);
        skin.add("scroll-knob-v", scrollKnobV,  Drawable.class);
        skin.add("list-sel",      listSel,      Drawable.class);

        registerLabelStyles(skin);
        registerTextButtonStyles(skin, card, cardOver, cardDisabled, primary, primaryOver);
        registerButtonStyles(skin, card, cardOver, cardOver);
        registerTextFieldStyles(skin, textfield, textfieldOver);
        registerSliderStyles(skin, sliderH, sliderKnobH);
        registerScrollPaneStyles(skin);
        registerSelectBoxStyles(skin, textfield, textfieldOver, listSel);
        registerListStyles(skin, listSel);
        registerCheckBoxStyles(skin, checkboxOn, checkboxOff);
        registerWindowStyles(skin, card);

        // Attach disposal tracking onto the skin via a tiny subclass is not
        // possible because we return a bare Skin. Instead we store the textures
        // on the skin's resources through a sentinel: we piggyback disposal by
        // wrapping — see createDisposing() if needed. For this app, the editor
        // skin lives for the application lifetime, so we attach textures to the
        // Skin itself as typed resources so skin.dispose() handles them.
        ownedTextures.forEach(t -> {
            skin.add("tex-" + System.identityHashCode(t), t, Texture.class);
        });

        return skin;
    }

    // =========================================================================
    // Pixmap drawing primitives
    // =========================================================================

    /** Create the stepped, four-tone frame used by the battle UI texture kit. */
    private NinePatchDrawable battleFrame(String name, Color fill, Color outer, Color light, Color shadow) {
        final int size = 18;
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setColor(fill);
        pm.fill();
        pm.setColor(outer);
        pm.drawRectangle(0, 0, size, size);
        pm.drawRectangle(1, 1, size - 2, size - 2);
        pm.setColor(light);
        pm.drawLine(3, size - 3, size - 4, size - 3);
        pm.drawLine(2, 3, 2, size - 4);
        pm.setColor(shadow);
        pm.drawLine(3, 2, size - 4, 2);
        pm.drawLine(size - 3, 3, size - 3, size - 4);

        pm.setColor(0f, 0f, 0f, 0f);
        pm.drawPixel(0, 0);
        pm.drawPixel(1, 0);
        pm.drawPixel(0, 1);
        pm.drawPixel(size - 1, 0);
        pm.drawPixel(size - 2, 0);
        pm.drawPixel(size - 1, 1);
        pm.drawPixel(0, size - 1);
        pm.drawPixel(1, size - 1);
        pm.drawPixel(0, size - 2);
        pm.drawPixel(size - 1, size - 1);
        pm.drawPixel(size - 2, size - 1);
        pm.drawPixel(size - 1, size - 2);

        return new NinePatchDrawable(new NinePatch(toTexture(pm, name), 5, 5, 5, 5));
    }

    /**
     * Draw a beveled 9-patch into a fresh Pixmap and return a NinePatchDrawable.
     *
     * The source image is large enough (size = pad*2 + 2) that every border
     * pixel ring is a complete rectangle (no gaps), and the 9-patch splits are
     * always strictly less than the texture size — this is what produces solid
     * continuous borders when stretched, instead of "4 dots" in the corners.
     *
     * @param pad    border padding per side in pixels (outer + inner + accent)
     * @param fill   interior fill colour
     * @param outer  outer border colour (1px)
     * @param inner  inner bevel colour (1px) — null to skip
     * @param accent accent line (1px) — null to skip
     */
    private NinePatchDrawable nine(String name, int pad, Color fill,
                                   Color outer, Color inner, Color accent) {
        int size = pad * 2 + 2;
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);

        // Solid fill across the whole pixmap first — guarantees corners are
        // the fill colour (no transparent gaps when stretched).
        pm.setColor(fill);
        pm.fill();

        // Border rings, drawn outer→inner so each is a complete rectangle.
        // ring 0 = outermost (0,0,size,size), ring 1 = 1px in, etc.
        if (outer != null) {
            pm.setColor(outer);
            pm.drawRectangle(0, 0, size, size);
        }
        if (inner != null && pad >= 2) {
            pm.setColor(inner);
            pm.drawRectangle(1, 1, size - 2, size - 2);
        }
        if (accent != null && pad >= 3) {
            pm.setColor(accent);
            pm.drawRectangle(2, 2, size - 4, size - 4);
        }

        Texture tex = toTexture(pm, name);
        NinePatch patch = new NinePatch(tex, pad, pad, pad, pad);
        return new NinePatchDrawable(patch);
    }

    /** A flat single-colour 9-patch (no bevel) tinted with a border. */
    private NinePatchDrawable patch(String name, Color fill) {
        return nine(name, 1, fill, new Color(fill.r, fill.g, fill.b, 1f), null, null);
    }

    /** Solid coloured pixmap for checkboxes (square, with 1px border). */
    private TextureRegionDrawable flat(String name, int size, Color fill, Color border) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setColor(fill);
        pm.fill();
        pm.setColor(border);
        pm.drawRectangle(0, 0, size, size);
        Texture tex = toTexture(pm, name);
        return new TextureRegionDrawable(new TextureRegion(tex));
    }

    private Texture toTexture(Pixmap pm, String name) {
        Texture tex = new Texture(pm);
        tex.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        pm.dispose();
        ownedTextures.add(tex);
        return tex;
    }

    private void addColors(Skin skin) {
        skin.add("text-dark",  TEXT_DARK,  Color.class);
        skin.add("text-light", Color.WHITE, Color.class);
        skin.add("text-error", TEXT_ERROR, Color.class);
        skin.add("text-ok",    TEXT_OK,    Color.class);
        skin.add("text-dirty", TEXT_DIRTY, Color.class);
        skin.add("text-dim",   TEXT_MUTED, Color.class);
        skin.add("text-hover", TEXT_HOVER, Color.class);
        skin.add("white",      Color.WHITE, Color.class);
        skin.add("black",      Color.BLACK, Color.class);
    }

    // =========================================================================
    // Style registration
    // =========================================================================

    private void registerLabelStyles(Skin skin) {
        com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(font, TEXT_DARK);
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle.class);

        com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle small =
            new com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(fontSmall, TEXT_DARK);
        skin.add("small", small, com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle.class);

        com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle title =
            new com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(fontLarge, Color.WHITE);
        skin.add("title", title, com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle.class);

        com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle error =
            new com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(fontSmall, TEXT_ERROR);
        skin.add("error", error, com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle.class);

        com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle ok =
            new com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(fontSmall, TEXT_OK);
        skin.add("ok", ok, com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle.class);

        com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle white =
            new com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(font, Color.WHITE);
        skin.add("white", white, com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle.class);
    }

    private void registerTextButtonStyles(Skin skin, Drawable card, Drawable cardOver, Drawable cardDisabled,
                                          Drawable primary, Drawable primaryOver) {
        com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle(card, cardOver, cardOver, font);
        def.fontColor = TEXT_DARK;
        def.downFontColor = TEXT_DARK;
        def.over = cardOver;
        def.overFontColor = TEXT_DARK;
        def.disabled = cardDisabled;
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle.class);

        com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle toggle =
            new com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle(card, cardOver, cardOver, font);
        toggle.fontColor = TEXT_DARK;
        toggle.checkedFontColor = TEXT_DARK;
        toggle.over = cardOver;
        toggle.overFontColor = TEXT_DARK;
        toggle.disabled = cardDisabled;
        skin.add("toggle", toggle, com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle.class);

        com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle primaryStyle =
            new com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle(primary, primary, primary, font);
        primaryStyle.fontColor = Color.WHITE;
        primaryStyle.downFontColor = Color.WHITE;
        primaryStyle.over = primaryOver;
        primaryStyle.overFontColor = Color.WHITE;
        primaryStyle.disabled = cardDisabled;
        primaryStyle.disabledFontColor = new Color(0.850f, 0.860f, 0.880f, 1f);
        skin.add("primary", primaryStyle, com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle.class);
    }

    private void registerButtonStyles(Skin skin, Drawable up, Drawable down, Drawable hover) {
        com.badlogic.gdx.scenes.scene2d.ui.Button.ButtonStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.Button.ButtonStyle(up, down, up);
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.Button.ButtonStyle.class);
    }

    private void registerTextFieldStyles(Skin skin, Drawable bg, Drawable hoverBg) {
        com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle(font, TEXT_DARK, null, null, bg);
        def.messageFontColor = new Color(0.45f, 0.45f, 0.45f, 1f);
        def.focusedBackground = hoverBg;
        // Use a 1-pixel selection drawable we already have
        def.selection = skin.getDrawable("selection");
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle.class);
    }

    private void registerSliderStyles(Skin skin, Drawable bg, Drawable knob) {
        com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle(bg, knob);
        skin.add("default-horizontal", def, com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle.class);
    }

    private void registerScrollPaneStyles(Skin skin) {
        com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle();
        def.background = skin.getDrawable("panel-inset");
        def.vScroll         = skin.getDrawable("scroll-v");
        def.vScrollKnob     = skin.getDrawable("scroll-knob-v");
        def.hScroll         = skin.getDrawable("scroll-h");
        def.hScrollKnob     = skin.getDrawable("scroll-knob-h");
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle.class);
    }

    private void registerSelectBoxStyles(Skin skin, Drawable bg, Drawable hoverBg, Drawable listSel) {
        com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle listStyle =
            new com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(font, TEXT_DARK, TEXT_DARK, listSel);
        // Pure-white list background (was parchment, which let the screen colour
        // bleed through).
        listStyle.background = skin.getDrawable("white-panel");
        skin.add("default", listStyle, com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle.class);

        // Dropdown ScrollPane: pure-white background so no parchment shows
        // behind the list. This is a dedicated style so the editor scroll panes
        // keep their parchment insets.
        com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle dropdownScroll =
            new com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle();
        dropdownScroll.background = skin.getDrawable("white-panel");
        dropdownScroll.vScroll     = skin.getDrawable("scroll-v");
        dropdownScroll.vScrollKnob = skin.getDrawable("scroll-knob-v");
        dropdownScroll.hScroll     = skin.getDrawable("scroll-h");
        dropdownScroll.hScrollKnob = skin.getDrawable("scroll-knob-h");
        skin.add("dropdown", dropdownScroll, com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle.class);

        // SelectBox: pure-white collapsed + open backgrounds, yellow hover text.
        com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle(
                font, TEXT_DARK, skin.getDrawable("white-panel"), dropdownScroll, listStyle);
        def.backgroundOver  = hoverBg;
        def.backgroundOpen  = hoverBg;
        def.overFontColor   = TEXT_HOVER;
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle.class);
    }

    private void registerListStyles(Skin skin, Drawable listSel) {
        // Already registered inside registerSelectBoxStyles; re-add under both
        // names to be safe.
    }

    private void registerCheckBoxStyles(Skin skin, Drawable on, Drawable off) {
        com.badlogic.gdx.scenes.scene2d.ui.CheckBox.CheckBoxStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.CheckBox.CheckBoxStyle(off, on, font, TEXT_DARK);
        // Hover highlights the label text (the checkbox box itself is handled
        // by the on/off drawables).
        def.overFontColor = TEXT_HOVER;
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.CheckBox.CheckBoxStyle.class);
    }

    private void registerWindowStyles(Skin skin, Drawable panel) {
        com.badlogic.gdx.scenes.scene2d.ui.Window.WindowStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.Window.WindowStyle(font, TEXT_DARK, panel);
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.Window.WindowStyle.class);
    }

    // =========================================================================
    // Fonts
    // =========================================================================

    private BitmapFont[] generateFonts() {
        com.badlogic.gdx.files.FileHandle ttf =
            Gdx.files.internal("assets/fonts/AtlantisInternational-jen0.ttf");

        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(ttf);
        FreeTypeFontParameter p = new FreeTypeFontParameter();
        p.borderWidth = 0f;

        // Oversampled (4x render + mipmap/linear downscale) so small editor text
        // keeps every stroke. Logical sizes match the previous on-screen size.
        // Editor body font
        BitmapFont body = AssetLoader.generateOversampled(gen, p, 19);
        body.setUseIntegerPositions(true);

        // Smaller font for tight UI (stat numbers, hints)
        BitmapFont small = AssetLoader.generateOversampled(gen, p, 15);
        small.setUseIntegerPositions(true);

        // Large font for titles / banners
        BitmapFont large = AssetLoader.generateOversampled(gen, p, 35);
        large.setUseIntegerPositions(true);

        gen.dispose();
        return new BitmapFont[]{ body, small, large };
    }
}
