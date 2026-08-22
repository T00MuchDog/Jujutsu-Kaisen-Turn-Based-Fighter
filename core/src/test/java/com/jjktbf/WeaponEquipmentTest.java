package com.jjktbf;

import com.jjktbf.model.character.Ability;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.AbilityResolver;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.character.CharacterStats;
import com.jjktbf.model.character.Equipment;
import com.jjktbf.model.character.SorcererCharacter;
import com.jjktbf.model.combat.BattleCombatant;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveCategory;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.move.MoveTag;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.TechniqueRepository;
import com.jjktbf.model.weapon.CursedToolData;
import com.jjktbf.model.weapon.CursedToolRepository;
import com.jjktbf.model.weapon.WeaponType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weapon equipment mechanics: base weapons and cursed tools gate weapon-tagged
 * moves, cursed tools waive matching jujutsu prerequisites and cursed energy
 * costs, and explicitly tool-assigned content is derived automatically.
 */
class WeaponEquipmentTest {

    private static Move katanaCeMove() {
        return new Move.Builder("KATANA_CE")
            .name("Cursed Sword Slash")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.CURSED_ENERGY, MoveTag.ATTACK, MoveTag.KATANA))
            .apCost(1).unleashPoint(1)
            .baseCeCost(20).hasCeCost(true).minCeCost(4).maxCeCost(85)
            .build();
    }

    @Test
    void cursedToolMakesWeaponMovesFreeButBaseWeaponDoesNot() {
        CharacterStats stats = new CharacterStats.Builder().build();
        Move move = katanaCeMove();
        Character toolUser = new SorcererCharacter("TOOL", "Tool User", stats, null,
            List.of(move), List.of(), Equipment.cursedTool(WeaponType.KATANA));
        Character baseUser = new SorcererCharacter("BASE", "Base User", stats, null,
            List.of(move), List.of(), Equipment.base(WeaponType.KATANA));

        assertEquals(0, new BattleCombatant(toolUser).computeMoveCeCost(move),
            "A cursed tool channels its own CE: its weapon type's moves are free.");
        assertEquals(20, new BattleCombatant(baseUser).computeMoveCeCost(move),
            "A base weapon does not waive the CE cost.");
    }

    @Test
    void cursedToolOnlyFreesItsOwnWeaponType() {
        CharacterStats stats = new CharacterStats.Builder().build();
        Move katanaMove = katanaCeMove();
        // A bow cursed tool equips the bow type, but the katana move still costs CE.
        Character bowUser = new SorcererCharacter("BOW", "Bow User", stats, null,
            List.of(), List.of(), Equipment.cursedTool(WeaponType.BOW));

        assertEquals(20, new BattleCombatant(bowUser).computeMoveCeCost(katanaMove));
    }

    @Test
    void weaponTypeUnlocksNormalAssignmentWithoutAutomaticallyGrantingMoves() {
        CursedToolData tool = new CursedToolData();
        tool.id = "000000";
        tool.name = "Test Polearm";
        tool.weaponType = "POLEARM";
        Move hardMove = new Move.Builder("HARD_MOVE")
            .name("Hard Move")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.POLEARM))
            .prerequisites(Map.of("strength", 300))
            .basePower(10).apCost(5).unleashPoint(1)
            .build();
        Equipment equipment = Equipment.resolve(
            List.of(), List.of(tool.id), List.of(tool),
            List.of(hardMove));

        Character unassigned = new SorcererCharacter(
            "NONE", "No Assignment", new CharacterStats.Builder().strength(300).build(),
            null, List.of(), List.of(), equipment);
        assertTrue(unassigned.getKnownMoves().isEmpty(),
            "A matching weapon type unlocks assignment; it does not teach the move.");

        CharacterStats lowStats = new CharacterStats.Builder().build();
        assertThrows(IllegalArgumentException.class, () -> new SorcererCharacter(
            "LOW", "Low Stats", lowStats, null, List.of(hardMove), List.of(), equipment),
            "Normal assignment must still enforce the move's stat prerequisites.");

        CharacterStats qualifyingStats = new CharacterStats.Builder().strength(300).build();
        Character eligible = assertDoesNotThrow(() -> new SorcererCharacter(
            "HIGH", "High Stats", qualifyingStats, null,
            List.of(hardMove), List.of(), equipment));
        assertTrue(eligible.getKnownMoves().stream()
            .anyMatch(move -> "HARD_MOVE".equals(move.getId())),
            "A matching equipped weapon type must satisfy the weapon gate.");
    }

    @Test
    void matchingCursedToolWaivesOnlyJujutsuStatPrerequisites() {
        Move move = new Move.Builder("CURSED_TOOL_MOVE")
            .name("Cursed Tool Move")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.CURSED_ENERGY, MoveTag.KATANA))
            .prerequisites(Map.of(
                "strength", 100,
                "speed", 100,
                "combatAbility", 100,
                "cursedEnergyReserves", 300,
                "cursedEnergyEfficiency", 300,
                "cursedEnergyOutput", 300,
                "jujutsuSkill", 300,
                "cursedTechniqueMastery", 300))
            .basePower(10).apCost(5).unleashPoint(1)
            .build();
        CharacterStats physicalRequirementsMet = new CharacterStats.Builder()
            .strength(100).speed(100).combatAbility(100)
            .build();

        Character wielder = assertDoesNotThrow(() -> new SorcererCharacter(
            "TOOL", "Tool User", physicalRequirementsMet, null,
            List.of(move), List.of(), Equipment.cursedTool(WeaponType.KATANA)));
        assertEquals(List.of(move.getId()),
            wielder.getKnownMoves().stream().map(Move::getId).toList());

        assertThrows(IllegalArgumentException.class, () -> new SorcererCharacter(
            "BASE", "Base Weapon User", physicalRequirementsMet, null,
            List.of(move), List.of(), Equipment.base(WeaponType.KATANA)),
            "A normal weapon must not waive jujutsu prerequisites.");

        CharacterStats lowStrength = new CharacterStats.Builder()
            .strength(99).speed(100).combatAbility(100)
            .build();
        assertThrows(IllegalArgumentException.class, () -> new SorcererCharacter(
            "WEAK", "Weak Tool User", lowStrength, null,
            List.of(move), List.of(), Equipment.cursedTool(WeaponType.KATANA)),
            "A cursed tool must not waive physical or combat prerequisites.");
    }

    @Test
    void canonicalHarutaCanLearnCursedKatanaSlashThroughHandSword() throws Exception {
        String previousAuthoring = System.getProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY);
        String previousRoot = System.getProperty(AppPaths.AUTHORING_ROOT_SYSTEM_PROPERTY);
        try {
            System.setProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY, "true");
            System.setProperty(AppPaths.AUTHORING_ROOT_SYSTEM_PROPERTY,
                System.getProperty("user.dir"));

            MoveRepository moves = new MoveRepository("data/moves");
            AbilityRepository abilities = new AbilityRepository("data/abilities");
            TechniqueRepository techniques = new TechniqueRepository("data/techniques");
            CursedToolRepository tools = new CursedToolRepository("data/tools");
            CharacterRepository characters = new CharacterRepository("data/characters");
            moves.load();
            abilities.load();
            techniques.load();
            tools.load();
            characters.load();

            CharacterData haruta = characters.findById("000000").orElseThrow();
            haruta.moveIds = new ArrayList<>(haruta.moveIds);
            haruta.moveIds.add("000022");

            Character built = assertDoesNotThrow(
                () -> haruta.toCharacter(moves, abilities, techniques, tools));
            assertTrue(built.getKnownMoves().stream()
                .anyMatch(move -> "000022".equals(move.getId())));
        } finally {
            restoreProperty(AppPaths.AUTHORING_SYSTEM_PROPERTY, previousAuthoring);
            restoreProperty(AppPaths.AUTHORING_ROOT_SYSTEM_PROPERTY, previousRoot);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @Test
    void exactToolAssignmentGrantsAnUntaggedMoveWithoutBypassingRequirements() {
        CursedToolData tool = new CursedToolData();
        tool.id = "000000";
        tool.name = "Special Tool";
        tool.weaponType = "STAFF";
        Move assigned = new Move.Builder("ASSIGNED")
            .name("Assigned Move")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL))
            .requiredCursedToolId(tool.id)
            .prerequisites(Map.of("speed", 100))
            .basePower(10).apCost(5).unleashPoint(1)
            .build();
        Equipment equipment = Equipment.resolve(
            List.of(), List.of(tool.id), List.of(tool), List.of(assigned));

        Character low = new SorcererCharacter("LOW", "Low", new CharacterStats.Builder().build(),
            null, List.of(), List.of(), equipment);
        Character high = new SorcererCharacter("HIGH", "High",
            new CharacterStats.Builder().speed(100).build(),
            null, List.of(), List.of(), equipment);

        assertTrue(low.getKnownMoves().isEmpty());
        assertEquals(List.of("ASSIGNED"),
            high.getKnownMoves().stream().map(Move::getId).toList());
    }

    @Test
    void multiWeaponMoveMatchesAnySelectedWeaponType() {
        CursedToolData bow = new CursedToolData();
        bow.id = "000000";
        bow.name = "Test Bow";
        bow.weaponType = "BOW";
        Move versatile = new Move.Builder("VERSATILE")
            .name("Versatile Strike")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.CURSED_ENERGY,
                MoveTag.KATANA, MoveTag.BOW))
            .basePower(10).baseCeCost(20).hasCeCost(true)
            .apCost(5).unleashPoint(1)
            .build();
        Equipment equipment = Equipment.resolve(
            List.of(), List.of(bow.id), List.of(bow), List.of(versatile));
        Character wielder = new SorcererCharacter(
            "BOW", "Bow User", new CharacterStats.Builder().build(), null,
            List.of(versatile), List.of(), equipment);

        assertEquals(List.of("VERSATILE"),
            wielder.getKnownMoves().stream().map(Move::getId).toList());
        assertEquals(0, new BattleCombatant(wielder).computeMoveCeCost(versatile));
        assertDoesNotThrow(() -> new SorcererCharacter(
            "BASE", "Base Bow User", new CharacterStats.Builder().build(), null,
            List.of(versatile), List.of(), Equipment.base(WeaponType.BOW)));
    }

    @Test
    void baseWeaponsDoNotAutomaticallyGrantTheirTaggedMoves() {
        Character wielder = new SorcererCharacter(
            "BASE", "Base User", new CharacterStats.Builder().build(), null,
            List.of(), List.of(), Equipment.base(WeaponType.KATANA));

        assertTrue(wielder.getKnownMoves().isEmpty());
    }

    @Test
    void resolvingBaseWeaponsDoesNotBuildTheMoveCatalog() {
        AtomicInteger conversions = new AtomicInteger();
        MoveData countedMove = new MoveData() {
            @Override public Move toMoveResolved(
                java.util.function.Function<String, MoveData> attackLaunchMoves
            ) {
                conversions.incrementAndGet();
                return katanaCeMove();
            }
        };
        countedMove.id = "COUNTED";
        MoveRepository moves = new MoveRepository("data/test-base-equipment-moves");
        moves.add(countedMove);
        CharacterData character = new CharacterData();
        character.equippedWeaponTypes = List.of(WeaponType.KATANA.name());

        Equipment equipment = character.resolveEquipment(moves, null, null);

        assertTrue(equipment.baseTypes().contains(WeaponType.KATANA));
        assertEquals(0, conversions.get());
    }

    @Test
    void cursedToolAbilityIsAutomaticallyAssignedOnlyWhileEquipped() {
        AbilityData ability = new AbilityData();
        ability.id = "ABILITY";
        ability.name = "Tool Ability";
        ability.category = "PASSIVE";
        ability.sourceType = "CURSED_TOOL";
        ability.sourceValue = "000000";
        ability.effects = List.of();
        CharacterData equipped = new CharacterData();
        equipped.equippedCursedToolIds = List.of("000000");
        CharacterData unequipped = new CharacterData();

        assertTrue(AbilityResolver.resolve(equipped, List.of(ability))
            .containsAbility(ability.id));
        assertTrue(!AbilityResolver.resolve(unequipped, List.of(ability))
            .containsAbility(ability.id));
    }

    @Test
    void automaticToolMoveRespectsTechniqueTreeActivation() {
        Move treeMove = new Move.Builder("TREE_MOVE")
            .name("Tree Move")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.KATANA))
            .requiredCursedToolId("000000")
            .requiredTechniqueId("Test Technique")
            .prerequisites(Map.of("cursedTechniqueMastery", 300))
            .basePower(10).apCost(5).unleashPoint(1)
            .build();
        MoveRepository moves = new MoveRepository("data/test-tool-tree-moves");
        moves.add(MoveData.fromMove(treeMove));
        AbilityRepository abilities = new AbilityRepository("data/test-tool-tree-abilities");
        TechniqueRepository techniques = new TechniqueRepository("data/test-tool-tree-techniques");
        InnateTechniqueData technique = new InnateTechniqueData();
        technique.id = "000000";
        technique.name = "Test Technique";
        techniques.add(technique);
        CursedToolRepository tools = new CursedToolRepository("data/test-tool-tree-tools");
        tools.add(tool("000000", WeaponType.KATANA));

        CharacterData character = characterWithTool("000000");
        character.innateTechniqueName = technique.name;
        Character locked = character.toCharacter(moves, abilities, techniques, tools);
        assertTrue(locked.getKnownMoves().isEmpty());

        character.availableMoveIds = List.of(treeMove.getId());
        Character unlocked = character.toCharacter(moves, abilities, techniques, tools);
        assertEquals(List.of(treeMove.getId()),
            unlocked.getKnownMoves().stream().map(Move::getId).toList());
    }

    @Test
    void equipmentGrantSatisfiesGrantOnlyValidationForPersistedMove() {
        Move grantOnly = new Move.Builder("GRANT_ONLY")
            .name("Grant Only")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.KATANA))
            .requiredCursedToolId("000000")
            .mustBeGranted(true)
            .basePower(10).apCost(5).unleashPoint(1)
            .build();
        MoveRepository moves = new MoveRepository("data/test-tool-grant-moves");
        moves.add(MoveData.fromMove(grantOnly));
        AbilityRepository abilities = new AbilityRepository("data/test-tool-grant-abilities");
        CursedToolRepository tools = new CursedToolRepository("data/test-tool-grant-tools");
        tools.add(tool("000000", WeaponType.KATANA));
        CharacterData character = characterWithTool("000000");
        character.moveIds = List.of(grantOnly.getId());

        Character built = assertDoesNotThrow(
            () -> character.toCharacter(moves, abilities, null, tools));
        assertEquals(List.of(grantOnly.getId()),
            built.getKnownMoves().stream().map(Move::getId).toList());
    }

    @Test
    void moveSourcedAbilityRecognizesAutomaticToolMove() {
        Move move = new Move.Builder("ABILITY_SOURCE")
            .name("Ability Source")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.KATANA))
            .requiredCursedToolId("000000")
            .basePower(10).apCost(5).unleashPoint(1)
            .build();
        MoveRepository moves = new MoveRepository("data/test-tool-source-moves");
        moves.add(MoveData.fromMove(move));
        AbilityData ability = new AbilityData();
        ability.id = "MOVE_ABILITY";
        ability.name = "Move Ability";
        ability.category = "PASSIVE";
        ability.sourceType = "MOVE";
        ability.sourceValue = move.getId();
        ability.effects = List.of();
        AbilityRepository abilities = new AbilityRepository("data/test-tool-source-abilities");
        abilities.add(ability);
        CursedToolRepository tools = new CursedToolRepository("data/test-tool-source-tools");
        tools.add(tool("000000", WeaponType.KATANA));
        CharacterData character = characterWithTool("000000");
        character.abilityIds = List.of(ability.id);

        Character built = character.toCharacter(moves, abilities, null, tools);
        assertEquals(List.of(ability.id),
            built.getAbilities().stream().map(Ability::getId).toList());
    }

    @Test
    void ineligibleToolCandidateCannotSourceAnAbility() {
        Move move = new Move.Builder("INELIGIBLE_SOURCE")
            .name("Ineligible Source")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.KATANA))
            .requiredCursedToolId("000000")
            .prerequisites(Map.of("strength", 300))
            .basePower(10).apCost(5).unleashPoint(1)
            .build();
        MoveRepository moves = new MoveRepository("data/test-ineligible-tool-source-moves");
        moves.add(MoveData.fromMove(move));
        AbilityData ability = new AbilityData();
        ability.id = "INELIGIBLE_MOVE_ABILITY";
        ability.name = "Ineligible Move Ability";
        ability.category = "PASSIVE";
        ability.sourceType = "MOVE";
        ability.sourceValue = move.getId();
        ability.effects = List.of();
        AbilityRepository abilities = new AbilityRepository(
            "data/test-ineligible-tool-source-abilities");
        abilities.add(ability);
        CursedToolRepository tools = new CursedToolRepository(
            "data/test-ineligible-tool-source-tools");
        tools.add(tool("000000", WeaponType.KATANA));
        CharacterData character = characterWithTool("000000");
        character.abilityIds = List.of(ability.id);

        assertThrows(IllegalArgumentException.class,
            () -> character.toCharacter(moves, abilities, null, tools));
    }

    @Test
    void persistedToolCandidateCannotBootstrapItsOwnMoveAbility() {
        Move move = new Move.Builder("CIRCULAR_SOURCE")
            .name("Circular Source")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.KATANA))
            .requiredCursedToolId("000000")
            .prerequisites(Map.of("strength", 300))
            .basePower(10).apCost(5).unleashPoint(1)
            .build();
        MoveRepository moves = new MoveRepository("data/test-circular-tool-source-moves");
        moves.add(MoveData.fromMove(move));
        AbilityEffectData grantMove = AbilityEffectType.GRANT_MOVE.createDefault();
        grantMove.moveId = move.getId();
        AbilityData ability = new AbilityData();
        ability.id = "CIRCULAR_MOVE_ABILITY";
        ability.name = "Circular Move Ability";
        ability.category = "PASSIVE";
        ability.sourceType = "MOVE";
        ability.sourceValue = move.getId();
        ability.effects = List.of(grantMove);
        AbilityRepository abilities = new AbilityRepository(
            "data/test-circular-tool-source-abilities");
        abilities.add(ability);
        CursedToolRepository tools = new CursedToolRepository(
            "data/test-circular-tool-source-tools");
        tools.add(tool("000000", WeaponType.KATANA));
        CharacterData character = characterWithTool("000000");
        character.moveIds = List.of(move.getId());
        character.abilityIds = List.of(ability.id);

        assertThrows(IllegalArgumentException.class,
            () -> character.toCharacter(moves, abilities, null, tools));
    }

    @Test
    void domainRoundTripDoesNotPersistDerivedToolContent() {
        Move move = new Move.Builder("DERIVED_MOVE")
            .name("Derived Move")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL, MoveTag.KATANA))
            .requiredCursedToolId("000000")
            .basePower(10).apCost(5).unleashPoint(1)
            .build();
        CursedToolData tool = tool("000000", WeaponType.KATANA);
        Equipment equipment = Equipment.resolve(
            List.of(), List.of(tool.id), List.of(tool), List.of(move));
        AbilityData ability = new AbilityData();
        ability.id = "DERIVED_ABILITY";
        ability.name = "Derived Ability";
        ability.category = "PASSIVE";
        ability.sourceType = "CURSED_TOOL";
        ability.sourceValue = tool.id;
        ability.effects = List.of();
        Move manualMove = new Move.Builder("MANUAL_MOVE")
            .name("Manual Move")
            .category(MoveCategory.PHYSICAL)
            .tags(Set.of(MoveTag.PHYSICAL))
            .basePower(10).apCost(5).unleashPoint(1)
            .build();
        AbilityData manualAbility = new AbilityData();
        manualAbility.id = "MANUAL_ABILITY";
        manualAbility.name = "Manual Ability";
        manualAbility.category = "PASSIVE";
        manualAbility.sourceType = "CHARACTER";
        manualAbility.effects = List.of();
        Character character = new SorcererCharacter(
            "TOOL", "Tool User", new CharacterStats.Builder().build(), null,
            List.of(manualMove), List.of(new Ability(manualAbility), new Ability(ability)), equipment);

        CharacterData persisted = CharacterData.fromCharacter(character);
        assertEquals(List.of(manualMove.getId()), persisted.moveIds);
        assertEquals(List.of(manualAbility.id), persisted.abilityIds);
        assertEquals(List.of(tool.id), persisted.equippedCursedToolIds);
    }

    private static CursedToolData tool(String id, WeaponType weaponType) {
        CursedToolData tool = new CursedToolData();
        tool.id = id;
        tool.name = "Test " + weaponType.displayName();
        tool.weaponType = weaponType.name();
        return tool;
    }

    private static CharacterData characterWithTool(String toolId) {
        CharacterData character = new CharacterData();
        character.id = "CHARACTER";
        character.name = "Character";
        character.moveIds = List.of();
        character.abilityIds = List.of();
        character.equippedCursedToolIds = List.of(toolId);
        return character;
    }

    @Test
    void resolvingUnknownEquippedToolFailsLoudly() {
        CursedToolData tool = new CursedToolData();
        tool.id = "000000";
        tool.name = "Test Katana";
        tool.weaponType = "KATANA";

        assertThrows(IllegalArgumentException.class, () -> Equipment.resolve(
                List.of(), List.of("999999"), List.of(tool),
                List.of()),
            "An equipped tool id that does not exist must fail loudly.");
        assertThrows(IllegalArgumentException.class, () -> Equipment.resolve(
                List.of("LIGHTSABER"), List.of(), List.of(tool),
                List.of()),
            "An unknown weapon type name must fail loudly.");
    }
}
