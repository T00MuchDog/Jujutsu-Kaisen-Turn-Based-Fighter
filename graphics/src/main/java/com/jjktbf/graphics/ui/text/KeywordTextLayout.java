package com.jjktbf.graphics.ui.text;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Rectangle;
import com.jjktbf.model.text.KeywordDescriptionCatalog;

import java.util.ArrayList;
import java.util.List;

/** Wraps and draws text while retaining hit boxes for highlighted keyword phrases. */
public final class KeywordTextLayout {

    public static final Color KEYWORD_ORANGE = new Color(0.945f, 0.400f, 0.075f, 1f);

    private final List<Run> runs;
    private final float scale;
    private final float lineHeight;
    private final int lineCount;

    private KeywordTextLayout(List<Run> runs, float scale, float lineHeight, int lineCount) {
        this.runs = List.copyOf(runs);
        this.scale = scale;
        this.lineHeight = lineHeight;
        this.lineCount = lineCount;
    }

    public static KeywordTextLayout build(
        BitmapFont font,
        String text,
        float maxWidth,
        int maxLines,
        float minimumScale,
        float lineHeightFactor
    ) {
        KeywordDescriptionCatalog catalog = KeywordDescriptionCatalog.getDefault();
        List<Token> tokens = tokens(text == null || text.isBlank() ? "-" : text, catalog);
        float originalScaleX = font.getData().scaleX;
        float originalScaleY = font.getData().scaleY;
        int lineLimit = Math.max(1, maxLines);
        float lowerScale = Math.max(0.1f, Math.min(1f, minimumScale));
        float layoutWidth = Math.max(1f, maxWidth);

        try {
            LayoutResult selected = null;
            float selectedScale = 1f;
            for (float candidate = 1f; candidate + 0.001f >= lowerScale; candidate -= 0.1f) {
                selectedScale = Math.max(lowerScale, candidate);
                font.getData().setScale(
                    originalScaleX * selectedScale,
                    originalScaleY * selectedScale);
                selected = layout(font, tokens, layoutWidth, lineHeightFactor);
                if (selected.lineCount <= lineLimit || selectedScale <= lowerScale + 0.001f) break;
            }
            if (selected == null) {
                return new KeywordTextLayout(List.of(), 1f, font.getLineHeight(), 1);
            }
            List<Run> visibleRuns = truncate(
                font, selected, lineLimit, layoutWidth);
            return new KeywordTextLayout(
                visibleRuns,
                selectedScale,
                selected.lineHeight,
                Math.min(selected.lineCount, lineLimit));
        } finally {
            font.getData().setScale(originalScaleX, originalScaleY);
        }
    }

    public float height() {
        return lineCount * lineHeight;
    }

    public void draw(
        Batch batch,
        BitmapFont font,
        float originX,
        float originTop,
        Color normalColor,
        Color keywordColor
    ) {
        float originalScaleX = font.getData().scaleX;
        float originalScaleY = font.getData().scaleY;
        Color originalColor = new Color(font.getColor());
        try {
            font.getData().setScale(originalScaleX * scale, originalScaleY * scale);
            for (Run run : runs) {
                font.setColor(run.entry == null ? normalColor : keywordColor);
                font.draw(batch, run.text, originX + run.x, originTop + run.baselineY);
            }
        } finally {
            font.getData().setScale(originalScaleX, originalScaleY);
            font.setColor(originalColor);
        }
    }

    /** Coordinates are relative to the same top-left origin supplied to {@link #draw}. */
    public KeywordHit keywordAt(float x, float y) {
        for (Run run : runs) {
            if (run.entry == null) continue;
            Rectangle bounds = new Rectangle(run.x, run.baselineY - lineHeight, run.width, lineHeight);
            if (bounds.contains(x, y)) return new KeywordHit(run.displayText, run.entry, bounds);
        }
        return null;
    }

    private static LayoutResult layout(
        BitmapFont font,
        List<Token> tokens,
        float maxWidth,
        float lineHeightFactor
    ) {
        List<Run> runs = new ArrayList<>();
        float lineHeight = font.getLineHeight() * Math.max(0.5f, lineHeightFactor);
        float spaceWidth = width(font, " ");
        float x = 0f;
        int line = 0;

        for (Token token : tokens) {
            if (token.newlineBefore) {
                line++;
                x = 0f;
            }
            float tokenWidth = width(font, token.text);
            float leadingSpace = token.spaceBefore && x > 0f ? spaceWidth : 0f;
            if (x > 0f && x + leadingSpace + tokenWidth > maxWidth) {
                line++;
                x = 0f;
                leadingSpace = 0f;
            }
            x += leadingSpace;
            String remaining = token.text;
            while (width(font, remaining) > maxWidth && remaining.length() > 1) {
                int end = fittingPrefix(font, remaining, maxWidth);
                String part = remaining.substring(0, end);
                float partWidth = width(font, part);
                runs.add(new Run(
                    part, token.displayText, token.entry, x, -line * lineHeight, partWidth, line));
                remaining = remaining.substring(end);
                line++;
                x = 0f;
            }
            float remainingWidth = width(font, remaining);
            runs.add(new Run(
                remaining,
                token.displayText,
                token.entry,
                x,
                -line * lineHeight,
                remainingWidth,
                line));
            x += remainingWidth;
        }
        return new LayoutResult(runs, lineHeight, line + 1);
    }

    private static List<Run> truncate(
        BitmapFont font,
        LayoutResult layout,
        int maxLines,
        float maxWidth
    ) {
        List<Run> visible = new ArrayList<>(layout.runs.stream()
            .filter(run -> run.line < maxLines)
            .toList());
        if (layout.lineCount <= maxLines) return visible;

        int lastLine = maxLines - 1;
        float ellipsisWidth = width(font, "...");
        while (!visible.isEmpty()) {
            Run last = visible.get(visible.size() - 1);
            if (last.line < lastLine || last.x + last.width + ellipsisWidth <= maxWidth) break;
            visible.remove(visible.size() - 1);
        }
        float x = 0f;
        if (!visible.isEmpty() && visible.get(visible.size() - 1).line == lastLine) {
            Run last = visible.get(visible.size() - 1);
            x = last.x + last.width;
        }
        visible.add(new Run(
            "...", "...", null, x, -lastLine * layout.lineHeight, ellipsisWidth, lastLine));
        return visible;
    }

    private static List<Token> tokens(String text, KeywordDescriptionCatalog catalog) {
        List<KeywordDescriptionCatalog.Match> matches = catalog.findMatches(text);
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        int matchIndex = 0;
        boolean spaceBefore = false;
        boolean newlineBefore = false;

        while (index < text.length()) {
            char current = text.charAt(index);
            if (Character.isWhitespace(current)) {
                newlineBefore |= current == '\n' || current == '\r';
                spaceBefore |= !newlineBefore;
                index++;
                continue;
            }

            KeywordDescriptionCatalog.Match match = matchIndex < matches.size()
                ? matches.get(matchIndex) : null;
            if (match != null && match.start() == index) {
                String displayedText = text.substring(match.start(), match.end());
                String[] words = displayedText.split("\\s+");
                for (int wordIndex = 0; wordIndex < words.length; wordIndex++) {
                    tokens.add(new Token(
                        words[wordIndex],
                        displayedText,
                        match.entry(),
                        wordIndex == 0 ? spaceBefore : true,
                        wordIndex == 0 && newlineBefore));
                }
                index = match.end();
                matchIndex++;
            } else {
                int nextMatch = match == null ? text.length() : match.start();
                int end = index + 1;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))
                    && end < nextMatch) {
                    end++;
                }
                String value = text.substring(index, end);
                tokens.add(new Token(value, value, null, spaceBefore, newlineBefore));
                index = end;
            }
            spaceBefore = false;
            newlineBefore = false;
        }
        return tokens;
    }

    private static float width(BitmapFont font, String text) {
        return new GlyphLayout(font, text).width;
    }

    private static int fittingPrefix(BitmapFont font, String word, float maxWidth) {
        int end = 1;
        while (end < word.length() && width(font, word.substring(0, end + 1)) <= maxWidth) end++;
        return end;
    }

    private record Token(
        String text,
        String displayText,
        KeywordDescriptionCatalog.Entry entry,
        boolean spaceBefore,
        boolean newlineBefore
    ) { }

    private record Run(
        String text,
        String displayText,
        KeywordDescriptionCatalog.Entry entry,
        float x,
        float baselineY,
        float width,
        int line
    ) { }

    private record LayoutResult(List<Run> runs, float lineHeight, int lineCount) { }

    public record KeywordHit(
        String text,
        KeywordDescriptionCatalog.Entry entry,
        Rectangle bounds
    ) { }
}
