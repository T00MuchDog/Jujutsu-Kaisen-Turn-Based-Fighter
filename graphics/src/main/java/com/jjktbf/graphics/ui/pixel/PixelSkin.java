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

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a complete Scene2D {@link Skin} entirely in code, drawing every UI
 * texture as a 9-patch pixel pixmap at startup (no binary asset files required).
 *
 * The visual language is Generation 3 FireRed/LeafGreen:
 *   - Cream / parchment window interiors with thick blue-beveled borders
 *   - Beveled buttons (yellow up, depressed-down)
 *   - Bright blue selection highlight
 *   - Thin pixel scrollbars
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

    // ── FRLG-inspired palette ──────────────────────────────────────────────────

    /** Deep blue used for the outer/thick window border and button bevel. */
    private static final Color BORDER_OUTER  = new Color(0.078f, 0.176f, 0.337f, 1f);   // #142A56
    /** Lighter blue inner edge of the bevel. */
    private static final Color BORDER_INNER  = new Color(0.388f, 0.580f, 0.835f, 1f);   // #6394D5
    /** Teal accent line just inside the border. */
    private static final Color BORDER_ACCENT = new Color(0.196f, 0.643f, 0.643f, 1f);   // #32A4A4
    /** Cream parchment window interior. */
    private static final Color PANEL_FILL    = new Color(0.973f, 0.949f, 0.808f, 1f);   // #F8F2CE
    /** Slightly darker parchment for inner panel insets / disabled textfields. */
    private static final Color PANEL_INSET   = new Color(0.910f, 0.870f, 0.710f, 1f);   // #E8DEB5

    /** Button up face — warm yellow. */
    private static final Color BUTTON_UP     = new Color(0.992f, 0.835f, 0.314f, 1f);   // #FDD550
    /** Button down / pressed face. */
    private static final Color BUTTON_DOWN   = new Color(0.776f, 0.588f, 0.180f, 1f);   // #C6962E
    /** Button hover rim. */
    private static final Color BUTTON_HOVER  = new Color(1.000f, 1.000f, 1.000f, 1f);   // white

    /** Vivid blue list/row selection. */
    private static final Color SELECTION     = new Color(0.247f, 0.498f, 0.835f, 1f);   // #3F7FD5

    /** Slider groove. */
    private static final Color SLIDER_BG     = new Color(0.600f, 0.600f, 0.620f, 1f);
    /** Slider knob face. */
    private static final Color SLIDER_KNOB   = new Color(0.247f, 0.498f, 0.835f, 1f);

    /** Scrollbar groove. */
    private static final Color SCROLL_BG     = new Color(0.760f, 0.760f, 0.760f, 1f);
    /** Scrollbar knob. */
    private static final Color SCROLL_KNOB   = new Color(0.380f, 0.420f, 0.560f, 1f);

    /** Dark navy text on cream panels. */
    private static final Color TEXT_DARK     = new Color(0.078f, 0.176f, 0.337f, 1f);   // matches BORDER_OUTER
    /** Red for error/validation feedback. */
    private static final Color TEXT_ERROR    = new Color(0.760f, 0.090f, 0.090f, 1f);
    /** Green for success / dirty-saved feedback. */
    private static final Color TEXT_OK       = new Color(0.090f, 0.470f, 0.180f, 1f);
    /** Amber for dirty-indicator. */
    private static final Color TEXT_DIRTY    = new Color(0.760f, 0.490f, 0.090f, 1f);

    /** Fonts generated and added to the skin. */
    private final BitmapFont font;
    private final BitmapFont fontSmall;

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
        ownedFonts.add(font);
        ownedFonts.add(fontSmall);
    }

    // =========================================================================
    // Build
    // =========================================================================

    private Skin build() {
        Skin skin = new Skin();

        skin.add("default", font, BitmapFont.class);
        skin.add("small",   fontSmall, BitmapFont.class);

        addColors(skin);

        // Drawable registry
        Drawable panel        = nine("panel",       4, PANEL_FILL,   BORDER_OUTER, BORDER_INNER, BORDER_ACCENT);
        Drawable panelInset   = nine("panel-inset", 4, PANEL_INSET,  BORDER_OUTER, BORDER_INNER, null);
        Drawable buttonUp     = nine("btn-up",      4, BUTTON_UP,    BORDER_OUTER, BORDER_INNER, null);
        Drawable buttonDown   = nine("btn-down",    4, BUTTON_DOWN,  BORDER_OUTER, null,         null);
        Drawable buttonHover  = nine("btn-hover",   4, BUTTON_UP,    BUTTON_HOVER, null,         null);
        Drawable buttonCheck  = nine("btn-checked", 4, BUTTON_DOWN,  BORDER_OUTER, BORDER_INNER, null);
        Drawable textfield    = nine("textfield",   3, Color.WHITE,  BORDER_OUTER, BORDER_INNER, null);
        Drawable selection    = patch("sel",        SELECTION);
        Drawable selectionRow = patch("sel-row",    new Color(SELECTION.r, SELECTION.g, SELECTION.b, 0.35f));
        Drawable sliderH      = patch("slider-bg-h",SLIDER_BG);
        Drawable sliderKnobH  = patch("slider-knob",SLIDER_KNOB);
        Drawable checkboxOn   = flat("chk-on",      11, Color.WHITE, BORDER_OUTER);
        Drawable checkboxOff  = flat("chk-off",     11, PANEL_INSET, BORDER_OUTER);
        Drawable scrollH      = patch("scroll-h",   SCROLL_BG);
        Drawable scrollV      = patch("scroll-v",   SCROLL_BG);
        Drawable scrollKnobH  = patch("scroll-knob-h", SCROLL_KNOB);
        Drawable scrollKnobV  = patch("scroll-knob-v", SCROLL_KNOB);
        Drawable listSel      = patch("list-sel",   new Color(SELECTION.r, SELECTION.g, SELECTION.b, 0.85f));

        skin.add("panel",         panel,        Drawable.class);
        skin.add("panel-inset",   panelInset,   Drawable.class);
        skin.add("button-up",     buttonUp,     Drawable.class);
        skin.add("button-down",   buttonDown,   Drawable.class);
        skin.add("button-hover",  buttonHover,  Drawable.class);
        skin.add("button-checked",buttonCheck,  Drawable.class);
        skin.add("textfield",     textfield,    Drawable.class);
        skin.add("selection",     selection,    Drawable.class);
        skin.add("selection-row", selectionRow, Drawable.class);
        skin.add("slider-h",      sliderH,      Drawable.class);
        skin.add("slider-knob",   sliderKnobH,  Drawable.class);
        skin.add("check-on",      checkboxOn,   Drawable.class);
        skin.add("check-off",     checkboxOff,  Drawable.class);
        skin.add("scroll-h",      scrollH,      Drawable.class);
        skin.add("scroll-v",      scrollV,      Drawable.class);
        skin.add("scroll-knob-h", scrollKnobH,  Drawable.class);
        skin.add("scroll-knob-v", scrollKnobV,  Drawable.class);
        skin.add("list-sel",      listSel,      Drawable.class);

        registerLabelStyles(skin);
        registerTextButtonStyles(skin, buttonUp, buttonDown, buttonHover, buttonCheck);
        registerButtonStyles(skin, buttonUp, buttonDown, buttonHover);
        registerTextFieldStyles(skin, textfield);
        registerSliderStyles(skin, sliderH, sliderKnobH);
        registerScrollPaneStyles(skin);
        registerSelectBoxStyles(skin, textfield, listSel);
        registerListStyles(skin, listSel);
        registerCheckBoxStyles(skin, checkboxOn, checkboxOff);
        registerWindowStyles(skin, panel);

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

    /**
     * Draw a beveled 9-patch into a fresh Pixmap and return a NinePatchDrawable.
     *
     * @param size   pixel size of the source image (square)
     * @param fill   interior fill colour
     * @param outer  outer border colour (1px)
     * @param inner  inner bevel colour (1px) — null to skip
     * @param accent accent line (1px) — null to skip
     */
    private NinePatchDrawable nine(String name, int size, Color fill,
                                   Color outer, Color inner, Color accent) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setColor(fill);
        pm.fill();

        // Outer border
        pm.setColor(outer);
        pm.drawRectangle(0, 0, size, size);

        // Inner bevel (1px inside the outer)
        if (inner != null) {
            pm.setColor(inner);
            pm.drawRectangle(1, 1, size - 2, size - 2);
        }

        // Accent line (1px inside the inner)
        if (accent != null) {
            pm.setColor(accent);
            pm.drawRectangle(2, 2, size - 4, size - 4);
        }

        Texture tex = toTexture(pm, name);
        // 9-patch splits: 1px outer + 1px inner (+1px accent) on each side.
        int pad = accent != null ? 3 : (inner != null ? 2 : 1);
        NinePatch patch = new NinePatch(tex, pad, pad, pad, pad);
        return new NinePatchDrawable(patch);
    }

    /** A flat single-colour 9-patch (no bevel) tinted with a border. */
    private NinePatchDrawable patch(String name, Color fill) {
        return nine(name, 4, fill, new Color(fill.r, fill.g, fill.b, 1f), null, null);
    }

    /** Solid coloured pixmap for checkboxes (square, with 1px border). */
    private TextureRegionDrawable flat(String name, int size, Color fill, Color border) {
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pm.setColor(fill);
        pm.fill();
        pm.setColor(border);
        pm.drawRectangle(0, 0, size, size);
        // inner bevel
        pm.setColor(new Color(1, 1, 1, 0.5f));
        pm.drawRectangle(1, 1, size - 2, size - 2);
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
        skin.add("text-dim",   new Color(0.42f, 0.42f, 0.46f, 1f), Color.class);
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
            new com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle(font, new Color(0.078f, 0.176f, 0.337f, 1f));
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

    private void registerTextButtonStyles(Skin skin, Drawable up, Drawable down, Drawable hover, Drawable checked) {
        com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle(up, down, up, font);
        def.fontColor = TEXT_DARK;
        def.downFontColor = Color.WHITE;
        def.overFontColor = TEXT_DARK;
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle.class);

        // Toggle variant — same look, checked shows pressed state.
        com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle toggle =
            new com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle(up, down, checked, font);
        toggle.fontColor = TEXT_DARK;
        toggle.checkedFontColor = Color.WHITE;
        skin.add("toggle", toggle, com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle.class);
    }

    private void registerButtonStyles(Skin skin, Drawable up, Drawable down, Drawable hover) {
        com.badlogic.gdx.scenes.scene2d.ui.Button.ButtonStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.Button.ButtonStyle(up, down, up);
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.Button.ButtonStyle.class);
    }

    private void registerTextFieldStyles(Skin skin, Drawable bg) {
        com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle(font, TEXT_DARK, null, null, bg);
        def.messageFontColor = new Color(0.45f, 0.45f, 0.45f, 1f);
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

    private void registerSelectBoxStyles(Skin skin, Drawable bg, Drawable listSel) {
        com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle listStyle =
            new com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(font, Color.WHITE, TEXT_DARK, listSel);
        listStyle.background = skin.getDrawable("panel-inset");
        skin.add("default", listStyle, com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle.class);

        com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle scrollStyle =
            skin.get(com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle.class);

        com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle(font, TEXT_DARK, bg, scrollStyle, listStyle);
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle.class);
    }

    private void registerListStyles(Skin skin, Drawable listSel) {
        // Already registered inside registerSelectBoxStyles; re-add under both
        // names to be safe.
    }

    private void registerCheckBoxStyles(Skin skin, Drawable on, Drawable off) {
        com.badlogic.gdx.scenes.scene2d.ui.CheckBox.CheckBoxStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.CheckBox.CheckBoxStyle(off, on, font, TEXT_DARK);
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.CheckBox.CheckBoxStyle.class);
    }

    private void registerWindowStyles(Skin skin, Drawable panel) {
        com.badlogic.gdx.scenes.scene2d.ui.Window.WindowStyle def =
            new com.badlogic.gdx.scenes.scene2d.ui.Window.WindowStyle(font, Color.WHITE, panel);
        skin.add("default", def, com.badlogic.gdx.scenes.scene2d.ui.Window.WindowStyle.class);
    }

    // =========================================================================
    // Fonts
    // =========================================================================

    private BitmapFont[] generateFonts() {
        com.badlogic.gdx.files.FileHandle ttf =
            Gdx.files.internal("assets/fonts/PressStart2P-Regular.ttf");

        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(ttf);
        FreeTypeFontParameter p = new FreeTypeFontParameter();
        p.borderWidth = 0f;
        p.genMipMaps = false;
        p.minFilter = Texture.TextureFilter.Nearest;
        p.magFilter = Texture.TextureFilter.Nearest;

        // Editor body font
        p.size = 10;
        BitmapFont body = gen.generateFont(p);
        body.setUseIntegerPositions(true);
        body.getData().setScale(1f);

        // Smaller font for tight UI (stat numbers, hints)
        p.size = 8;
        BitmapFont small = gen.generateFont(p);
        small.setUseIntegerPositions(true);

        gen.dispose();
        return new BitmapFont[]{ body, small };
    }
}
