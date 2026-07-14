package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.NinePatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-drawn, nearest-neighbour pixel textures used by the battle planner.
 *
 * <p>The planner deliberately uses a small texture kit rather than procedural
 * vector widgets. The pieces are nine-patch safe, so the chunky pixel borders
 * keep their proportions at every game resolution.</p>
 */
public final class BattleUiAssets {

    public static final Color INK = new Color(0.055f, 0.075f, 0.125f, 1f);
    public static final Color PAPER = new Color(0.965f, 0.949f, 0.890f, 1f);
    public static final Color TEXT = new Color(0.090f, 0.125f, 0.205f, 1f);
    public static final Color MUTED = new Color(0.400f, 0.445f, 0.545f, 1f);
    public static final Color YELLOW = new Color(1.000f, 0.835f, 0.180f, 1f);
    public static final Color CURSED_ENERGY = new Color(0.160f, 0.430f, 0.810f, 1f);
    public static final Color OFFENSE = new Color(0.920f, 0.350f, 0.180f, 1f);
    public static final Color DEFENSE = new Color(0.220f, 0.470f, 0.920f, 1f);

    private final List<Texture> ownedTextures = new ArrayList<>();

    public final NinePatch header;
    public final NinePatch palette;
    public final NinePatch card;
    public final NinePatch cardOver;
    public final NinePatch cardDisabled;
    public final NinePatch segment;
    public final NinePatch segmentActive;
    public final NinePatch lockButton;
    public final NinePatch lockButtonOver;
    public final NinePatch lockButtonDisabled;
    public final NinePatch offenseTrack;
    public final NinePatch defenseTrack;
    public final NinePatch trackTarget;
    public final NinePatch statPill;
    public final Texture dot;
    public final Texture majorDot;
    public final Texture pixel;
    public final Texture offenseIcon;
    public final Texture defenseIcon;

    public BattleUiAssets() {
        header = frame(new Color(0.110f, 0.145f, 0.235f, 1f),
            new Color(0.035f, 0.050f, 0.095f, 1f), new Color(0.390f, 0.610f, 0.940f, 1f),
            new Color(0.030f, 0.045f, 0.085f, 1f));
        palette = frame(new Color(0.150f, 0.185f, 0.280f, 1f),
            new Color(0.045f, 0.060f, 0.110f, 1f), new Color(0.360f, 0.450f, 0.660f, 1f),
            new Color(0.025f, 0.035f, 0.070f, 1f));
        card = frame(PAPER, new Color(0.105f, 0.135f, 0.205f, 1f),
            new Color(1f, 1f, 1f, 1f), new Color(0.700f, 0.680f, 0.590f, 1f));
        cardOver = frame(new Color(1f, 0.985f, 0.840f, 1f), new Color(0.965f, 0.670f, 0.120f, 1f),
            new Color(1f, 1f, 0.780f, 1f), new Color(0.720f, 0.420f, 0.080f, 1f));
        cardDisabled = frame(new Color(0.660f, 0.670f, 0.700f, 1f), new Color(0.300f, 0.320f, 0.380f, 1f),
            new Color(0.800f, 0.810f, 0.840f, 1f), new Color(0.440f, 0.450f, 0.500f, 1f));
        segment = frame(new Color(1f, 1f, 0.980f, 1f), new Color(0.075f, 0.095f, 0.145f, 1f),
            new Color(1f, 1f, 1f, 1f), new Color(0.630f, 0.650f, 0.720f, 1f));
        segmentActive = frame(new Color(1f, 0.985f, 0.760f, 1f), new Color(0.980f, 0.700f, 0.100f, 1f),
            new Color(1f, 1f, 0.700f, 1f), new Color(0.720f, 0.430f, 0.050f, 1f));
        lockButton = frame(new Color(0.200f, 0.630f, 0.390f, 1f), new Color(0.030f, 0.180f, 0.090f, 1f),
            new Color(0.510f, 0.900f, 0.610f, 1f), new Color(0.050f, 0.330f, 0.150f, 1f));
        lockButtonOver = frame(new Color(0.300f, 0.760f, 0.440f, 1f), new Color(1f, 0.840f, 0.180f, 1f),
            new Color(0.780f, 1f, 0.730f, 1f), new Color(0.100f, 0.430f, 0.180f, 1f));
        lockButtonDisabled = frame(new Color(0.370f, 0.410f, 0.440f, 1f), new Color(0.170f, 0.190f, 0.230f, 1f),
            new Color(0.550f, 0.580f, 0.610f, 1f), new Color(0.240f, 0.270f, 0.300f, 1f));
        offenseTrack = frame(new Color(0.070f, 0.085f, 0.135f, 1f), new Color(0.450f, 0.145f, 0.080f, 1f),
            OFFENSE, new Color(0.180f, 0.060f, 0.040f, 1f));
        defenseTrack = frame(new Color(0.070f, 0.085f, 0.135f, 1f), new Color(0.080f, 0.220f, 0.520f, 1f),
            DEFENSE, new Color(0.035f, 0.090f, 0.250f, 1f));
        trackTarget = frame(new Color(0.100f, 0.100f, 0.110f, 1f), new Color(0.900f, 0.600f, 0.080f, 1f),
            YELLOW, new Color(0.430f, 0.250f, 0.030f, 1f));
        statPill = frame(new Color(0.890f, 0.915f, 0.980f, 1f), new Color(0.280f, 0.360f, 0.540f, 1f),
            Color.WHITE, new Color(0.560f, 0.640f, 0.800f, 1f));

        dot = dotTexture(false);
        majorDot = dotTexture(true);
        pixel = solidPixel();
        offenseIcon = offenseIconTexture();
        defenseIcon = defenseIconTexture();
    }

    public NinePatch track(TimelineBar.Kind kind, boolean targeted) {
        if (targeted) return trackTarget;
        return kind == TimelineBar.Kind.OFFENSIVE ? offenseTrack : defenseTrack;
    }

    public NinePatch segment(boolean highlighted) {
        return highlighted ? segmentActive : segment;
    }

    public void dispose() {
        for (Texture texture : ownedTextures) texture.dispose();
        ownedTextures.clear();
    }

    private NinePatch frame(Color fill, Color outer, Color light, Color shadow) {
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

        // Hard square corners are replaced with a stepped pixel cut.
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

        Texture texture = texture(pm);
        return new NinePatch(texture, 5, 5, 5, 5);
    }

    private Texture dotTexture(boolean major) {
        Pixmap pm = new Pixmap(major ? 5 : 3, major ? 5 : 3, Pixmap.Format.RGBA8888);
        pm.setColor(major ? Color.WHITE : new Color(0.870f, 0.910f, 1f, 1f));
        pm.fill();
        return texture(pm);
    }

    private Texture solidPixel() {
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        return texture(pm);
    }

    private Texture offenseIconTexture() {
        Pixmap pm = clearIconPixmap();
        pm.setColor(OFFENSE);
        pm.drawLine(3, 2, 13, 12);
        pm.drawLine(4, 2, 13, 11);
        pm.drawLine(2, 6, 7, 6);
        pm.drawLine(4, 4, 4, 9);
        return texture(pm);
    }

    private Texture defenseIconTexture() {
        Pixmap pm = clearIconPixmap();
        pm.setColor(DEFENSE);
        pm.drawLine(4, 12, 11, 12);
        pm.drawLine(3, 11, 12, 11);
        pm.drawLine(3, 7, 3, 10);
        pm.drawLine(12, 7, 12, 10);
        pm.drawLine(4, 5, 11, 5);
        pm.drawLine(5, 4, 10, 4);
        pm.drawLine(4, 6, 4, 8);
        pm.drawLine(11, 6, 11, 8);
        pm.drawLine(5, 3, 10, 3);
        return texture(pm);
    }

    private Pixmap clearIconPixmap() {
        Pixmap pm = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
        pm.setColor(0f, 0f, 0f, 0f);
        pm.fill();
        return pm;
    }

    private Texture texture(Pixmap pm) {
        Texture texture = new Texture(pm);
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        pm.dispose();
        ownedTextures.add(texture);
        return texture;
    }
}
