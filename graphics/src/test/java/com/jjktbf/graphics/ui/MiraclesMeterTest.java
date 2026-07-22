package com.jjktbf.graphics.ui;

import com.jjktbf.model.character.coded.CodedAbilityState;
import com.jjktbf.model.character.coded.MiraclesAbility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiraclesMeterTest {

    @Test
    void onlyMiraclesStateSelectsAClampedCounterGraphic() {
        MiraclesMeter meter = new MiraclesMeter();

        meter.setState(new CodedAbilityState("OTHER", "Other", 6, 6));
        assertFalse(meter.isVisible());

        meter.setState(new CodedAbilityState(MiraclesAbility.KEY, "Miracles", 4, 6));
        assertTrue(meter.isVisible());
        assertEquals(4, meter.imageIndex());

        meter.setState(new CodedAbilityState(MiraclesAbility.KEY, "Miracles", 9, 6));
        assertEquals(MiraclesAbility.MAX_MIRACLES, meter.imageIndex());

        meter.setState(new CodedAbilityState(MiraclesAbility.KEY, "Miracles", -1, 6));
        assertEquals(0, meter.imageIndex());
    }
}
