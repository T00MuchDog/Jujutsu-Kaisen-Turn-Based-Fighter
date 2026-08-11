package com.jjktbf.graphics.ui.battle;

import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveTag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BattleUiAssetsTest {

    @Test
    void innateTechniqueAttackResolvesItsCategorySpecificAsset() {
        Move cursedSpeech = innateTechniqueMove("Cursed Speech", MoveCategory.INNATE_TECHNIQUE,
            EnumSet.of(MoveTag.INNATE_TECHNIQUE, MoveTag.ATTACK));

        String path = BattleUiAssets.techniqueEffectIconPathFor(cursedSpeech);

        assertEquals("assets/moves/cursedTechniques/cursedSpeech/Attack_Icon.png", path);
        assertNotNull(getClass().getClassLoader().getResource(path));
    }

    @Test
    void ordinaryMoveHasNoTechniqueSpecificAssetCandidate() {
        Move ordinaryAttack = new Move.Builder("ORDINARY_ATTACK")
            .category(MoveCategory.PHYSICAL)
            .tags(EnumSet.of(MoveTag.PHYSICAL, MoveTag.ATTACK))
            .build();

        assertNull(BattleUiAssets.techniqueEffectIconPathFor(ordinaryAttack));
    }

    private static Move innateTechniqueMove(
        String techniqueId,
        MoveCategory category,
        EnumSet<MoveTag> tags
    ) {
        return new Move.Builder("TECHNIQUE_MOVE")
            .category(category)
            .tags(tags)
            .prerequisites(Map.of("cursedTechniqueMastery", 0))
            .requiredTechniqueId(techniqueId)
            .build();
    }
}
