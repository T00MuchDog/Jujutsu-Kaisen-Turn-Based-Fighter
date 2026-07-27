package com.jjktbf.graphics.ui;

import com.jjktbf.model.character.coded.CodedAbilityState;
import com.jjktbf.model.character.coded.RatioAbility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatioMeterTest {

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

        meter.setState(new CodedAbilityState(RatioAbility.KEY, "Ratio", 9, 3));
        assertEquals(RatioAbility.MAX_STACKS, meter.stackCount());
    }
}
