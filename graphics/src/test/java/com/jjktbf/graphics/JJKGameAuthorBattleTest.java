package com.jjktbf.graphics;

import com.jjktbf.AppPaths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JJKGameAuthorBattleTest {

    @Test
    void controlBothTeamsRouteIsRejectedOutsideAuthoringMode() {
        String previous = System.getProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY);
        try {
            System.clearProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY);

            assertThrows(IllegalStateException.class, () -> new JJKGame().showAuthorBattle());
        } finally {
            if (previous == null) {
                System.clearProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY);
            } else {
                System.setProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY, previous);
            }
        }
    }
}
