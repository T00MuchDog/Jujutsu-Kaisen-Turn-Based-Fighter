package com.jjktbf;

import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.BattlePlan;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveCapTest {

    @Test
    void moveCapRoundTripsAndRejectsNegativeValues() {
        Move capped = move("CAPPED", 1);

        MoveData data = MoveData.fromMove(capped);
        assertEquals(1, data.moveCap);
        assertEquals(1, data.toMove().getMoveCap());

        assertThrows(IllegalStateException.class, () -> move("INVALID", -1));
    }

    @Test
    void battlePlanEnforcesCapAndRemovalRestoresAvailability() {
        Move capped = move("CAPPED", 1);
        BattlePlan plan = new BattlePlan(30, 0, 30);

        ActionSegment first = plan.place(capped, 1, 0);
        assertNotNull(first);
        assertEquals(1, plan.selectedUses(capped));
        assertNull(plan.place(capped, 6, 0));

        assertTrue(plan.remove(first));
        assertNotNull(plan.place(capped, 6, 0));
    }

    @Test
    void zeroCapIsUnlimitedAndClearResetsDerivedCount() {
        Move unlimited = move("UNLIMITED", 0);
        BattlePlan plan = new BattlePlan(30, 0, 30);

        assertNotNull(plan.place(unlimited, 1, 0));
        assertNotNull(plan.place(unlimited, 6, 0));
        assertEquals(2, plan.selectedUses(unlimited));

        plan.clear();
        assertEquals(0, plan.selectedUses(unlimited));
        assertNotNull(plan.place(unlimited, 1, 0));
    }

    private static Move move(String id, int cap) {
        return new Move.Builder(id)
            .name(id)
            .category(MoveCategory.PHYSICAL)
            .basePower(20)
            .apCost(5)
            .unleashPoint(1)
            .moveCap(cap)
            .build();
    }
}
