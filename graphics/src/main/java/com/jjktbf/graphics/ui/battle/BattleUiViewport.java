package com.jjktbf.graphics.ui.battle;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;

/** Maps a fixed logical battle canvas into a host-window/back-buffer viewport. */
public record BattleUiViewport(
    float logicalWidth,
    float logicalHeight,
    float screenX,
    float screenY,
    float screenWidth,
    float screenHeight,
    int backBufferX,
    int backBufferY,
    int backBufferWidth,
    int backBufferHeight
) {
    public record LogicalPoint(float x, float y) { }

    public BattleUiViewport {
        if (logicalWidth <= 0f || logicalHeight <= 0f) {
            throw new IllegalArgumentException("Logical viewport dimensions must be positive");
        }
        if (screenWidth <= 0f || screenHeight <= 0f
            || backBufferWidth <= 0 || backBufferHeight <= 0) {
            throw new IllegalArgumentException("Host viewport dimensions must be positive");
        }
    }

    public static BattleUiViewport fullScreen() {
        int screenWidth = Math.max(1, Gdx.graphics.getWidth());
        int screenHeight = Math.max(1, Gdx.graphics.getHeight());
        return new BattleUiViewport(
            screenWidth,
            screenHeight,
            0f,
            0f,
            screenWidth,
            screenHeight,
            0,
            0,
            Math.max(1, Gdx.graphics.getBackBufferWidth()),
            Math.max(1, Gdx.graphics.getBackBufferHeight())
        );
    }

    public static BattleUiViewport fit(
        float logicalWidth,
        float logicalHeight,
        int hostScreenWidth,
        int hostScreenHeight,
        int hostBackBufferWidth,
        int hostBackBufferHeight
    ) {
        float scale = Math.min(
            hostScreenWidth / logicalWidth,
            hostScreenHeight / logicalHeight);
        float fittedWidth = Math.max(1f, logicalWidth * scale);
        float fittedHeight = Math.max(1f, logicalHeight * scale);
        float fittedX = (hostScreenWidth - fittedWidth) / 2f;
        float fittedY = (hostScreenHeight - fittedHeight) / 2f;
        float backBufferScaleX = hostBackBufferWidth / (float) hostScreenWidth;
        float backBufferScaleY = hostBackBufferHeight / (float) hostScreenHeight;
        return new BattleUiViewport(
            logicalWidth,
            logicalHeight,
            fittedX,
            fittedY,
            fittedWidth,
            fittedHeight,
            Math.round(fittedX * backBufferScaleX),
            Math.round(fittedY * backBufferScaleY),
            Math.max(1, Math.round(fittedWidth * backBufferScaleX)),
            Math.max(1, Math.round(fittedHeight * backBufferScaleY))
        );
    }

    public void apply(SpriteBatch batch) {
        Gdx.gl.glViewport(backBufferX, backBufferY, backBufferWidth, backBufferHeight);
        batch.getProjectionMatrix().setToOrtho2D(0f, 0f, logicalWidth, logicalHeight);
    }

    public Rectangle scissor(Rectangle logicalBounds) {
        float scaleX = backBufferWidth / logicalWidth;
        float scaleY = backBufferHeight / logicalHeight;
        int x = backBufferX + Math.round(logicalBounds.x * scaleX);
        int y = backBufferY + Math.round(logicalBounds.y * scaleY);
        int width = Math.round(logicalBounds.width * scaleX);
        int height = Math.round(logicalBounds.height * scaleY);

        int left = Math.max(backBufferX, x);
        int bottom = Math.max(backBufferY, y);
        int right = Math.min(backBufferX + backBufferWidth, x + width);
        int top = Math.min(backBufferY + backBufferHeight, y + height);
        return new Rectangle(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
    }

    /** Input coordinates use a bottom-left origin here, matching battle coordinates. */
    public LogicalPoint toLogical(float hostScreenX, float hostScreenY) {
        if (hostScreenX < screenX || hostScreenX > screenX + screenWidth
            || hostScreenY < screenY || hostScreenY > screenY + screenHeight) {
            return null;
        }
        return new LogicalPoint(
            (hostScreenX - screenX) * logicalWidth / screenWidth,
            (hostScreenY - screenY) * logicalHeight / screenHeight);
    }

    public static void restoreHostViewport() {
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glViewport(
            0, 0,
            Math.max(1, Gdx.graphics.getBackBufferWidth()),
            Math.max(1, Gdx.graphics.getBackBufferHeight()));
    }
}
