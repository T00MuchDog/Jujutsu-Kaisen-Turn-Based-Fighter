package com.jjktbf.graphics.ui;

import com.jjktbf.model.character.coded.CodedAbilityState;
import com.jjktbf.model.character.coded.RatioAbility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatioMeterTest {

    @Test
    void windowsGeometryEnlargesRatioHeightOnlyWhenRequested() {
        assertEquals(90f, RatioMeter.heightForViewport(1440f), 0.0001f);
        assertEquals(135f, RatioMeter.heightForViewport(1440f, 1.5f), 0.0001f);
    }

    @Test
    void onlyActiveRatioStacksShowTheGraphicAndMultiplierCount() {
        RatioMeter meter = new RatioMeter();

        meter.setState(new CodedAbilityState("OTHER", "Other", 2, 3));
        assertFalse(meter.isVisible());

        meter.setState(new CodedAbilityState(RatioAbility.KEY, "Ratio", 0, 3));
        assertFalse(meter.isVisible());

        meter.setState(new CodedAbilityState(RatioAbility.KEY, "Ratio", 2, 3));
        assertTrue(meter.isVisible());
        assertEquals(2, meter.stackCount());

        meter.setState(new CodedAbilityState(RatioAbility.KEY, "Ratio", 5, 5));
        assertEquals(5, meter.stackCount());
    }
}
