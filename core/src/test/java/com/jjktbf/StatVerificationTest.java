package com.jjktbf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.*;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CombatStats;
import com.jjktbf.model.combat.*;
import com.jjktbf.model.move.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

public class StatVerificationTest {

    @Test
    void baselineApBarShouldBeAround80() {
        CharacterStats stats = new CharacterStats.Builder()
            .speed(80).combatAbility(80).build();
        CombatStats cs = new CombatStats(stats);
        int ap = cs.getMaxApBar();
        System.out.println("Baseline AP bar: " + ap);
        // (80*15 + 80*3) / 18 = 1440/18 = 80 exactly
        assertEquals(80, ap, "Expected baseline AP = 80 exactly");
    }

    @Test
    void highStatApBarShouldBeAround380() {
        CharacterStats stats = new CharacterStats.Builder()
            .speed(300).combatAbility(300).build();
        CombatStats cs = new CombatStats(stats);
        int ap = cs.getMaxApBar();
        System.out.println("Max AP bar (300/300): " + ap);
        // (300*15 + 300*3) / 18 = 5400/18 = 300 exactly
        assertEquals(300, ap, "Expected max AP = 300 exactly");
    }

    @Test
    void equalAccuracyEvasionShouldGive95PercentHitOn100BaseAccuracy() {
        double hit = CombatStats.computeHitChance(80, 80, 1.0);
        System.out.printf("Hit chance (equal stats, 100%% base): %.4f%n", hit);
        assertEquals(0.95, hit, 0.001, "Expected 95% hit chance on equal stats");
    }

    /** CE-cost fixture: baseCeCost=20, minCeCost=8, maxCeCost=40 (hasCeCost=true). */
    private static Move ceCostFixture() {
        return new Move.Builder("CE_FIXTURE")
            .name("CE Fixture")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .baseCeCost(20).hasCeCost(true).minCeCost(8).maxCeCost(40)
            .build();
    }

    @Test
    void ceEfficiencyBaselineShouldNotChangeCost() {
        // Baseline efficiency = 80, so factor = 80/80 = 1.0, no change
        var move = ceCostFixture(); // baseCeCost = 20
        int cost = com.jjktbf.model.combat.CeEfficiencyCalculator.computeActualCost(move, 80);
        System.out.println("CE cost at baseline efficiency: " + cost);
        assertEquals(20, cost);
    }

    @Test
    void highCeEfficiencyShouldReduceCost() {
        var move = ceCostFixture(); // baseCeCost=20, min=8, max=40
        int cost = com.jjktbf.model.combat.CeEfficiencyCalculator.computeActualCost(move, 200);
        System.out.println("CE cost at efficiency 200: " + cost);
        assertTrue(cost < 20 && cost >= 8, "Cost should be reduced but above min=8, got: " + cost);
    }

    @Test
    void lowCeEfficiencyShouldIncreaseCost() {
        var move = ceCostFixture(); // baseCeCost=20, min=8, max=40
        int cost = com.jjktbf.model.combat.CeEfficiencyCalculator.computeActualCost(move, 30);
        System.out.println("CE cost at efficiency 30: " + cost);
        assertTrue(cost > 20 && cost <= 40, "Cost should be increased but below max=40, got: " + cost);
    }

    @Test
    void zeroCeCostCanStillBeExplicitlyCeBased() {
        Move move = new Move.Builder("ZERO_CE")
            .name("Zero CE Technique")
            .category(MoveCategory.PHYSICAL)
            .apCost(10)
            .unleashPoint(1)
            .baseCeCost(0)
            .hasCeCost(true)
            .minCeCost(0)
            .maxCeCost(0)
            .build();

        assertTrue(move.hasCeCost());
        assertEquals(0, CeEfficiencyCalculator.computeActualCost(move, 80));
    }

    @Test
    void ceOutputUpTemporarilyRaisesCursedEnergyOutput() {
        CharacterStats stats = new CharacterStats.Builder().cursedEnergyOutput(80).build();
        Character character = new SorcererCharacter("CE_OUT", "Output Test", stats, null, List.of());
        BattleCombatant combatant = new BattleCombatant(character);

        combatant.addStatusEffect(new StatusEffect(StatusEffectType.CE_OUTPUT_UP, 1, 15));

        assertEquals(95, combatant.getEffectiveStats().getCursedEnergyOutput());
        combatant.tickStatusEffects();
        assertEquals(80, combatant.getEffectiveStats().getCursedEnergyOutput());
    }

    @Test
    void focusTemporarilyRaisesBaseAccuracy() {
        Character character = new SorcererCharacter(
            "FOCUS", "Focus Test", new CharacterStats.Builder().build(), null, List.of());
        BattleCombatant combatant = new BattleCombatant(character);

        combatant.addStatusEffect(new StatusEffect(StatusEffectType.FOCUS, 1, 0.1));

        assertEquals(0.1, combatant.getStatusBaseAccuracyBonus(), 0.0001);
        combatant.tickStatusEffects();
        assertEquals(0.0, combatant.getStatusBaseAccuracyBonus(), 0.0001);
    }

    @Test
    void bundledMoveDataLoadsAndBuilds() throws IOException {
        Path movesPath = List.of(
                Path.of("data", "moves", "all_moves.json"),
                Path.of("..", "data", "moves", "all_moves.json"))
            .stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IOException("Could not locate bundled move data"));

        List<MoveData> moves = new ObjectMapper().readValue(
            movesPath.toFile(), new TypeReference<List<MoveData>>() {});

        assertEquals(25, moves.size());
        moves.forEach(move -> assertDoesNotThrow(move::toMove, move.name));

        MoveData surge = moves.stream()
            .filter(move -> "Cursed Energy Surge".equals(move.name))
            .findFirst()
            .orElseThrow();
        assertEquals(StatusEffectType.CE_OUTPUT_UP.name(), surge.selfEffects.get(0).type);
        assertEquals(15.0, surge.selfEffects.get(0).magnitude);
    }

    @Test
    void nonDamagingMovesRetainTheirUnderlyingNatureTags() {
        MoveData data = new MoveData();
        data.id = "RAW_TAGS";
        data.name = "CE Guard";
        data.tags = List.of("DEFENSIVE", "PHYSICAL", "CURSED_ENERGY");
        data.apCost = 10;
        data.unleashPoint = 1;

        Move move = data.toMove();

        assertEquals(MoveCategory.DEFENSIVE, move.getCategory());
        assertTrue(move.hasTag("DEFENSIVE"));
        assertTrue(move.hasTag("PHYSICAL"));
        assertTrue(move.hasTag("CURSED_ENERGY"));

        MoveData roundTripped = MoveData.fromMove(move);
        assertTrue(roundTripped.tags.contains("DEFENSIVE"));
        assertTrue(roundTripped.tags.contains("PHYSICAL"));
        assertTrue(roundTripped.tags.contains("CURSED_ENERGY"));
    }

    @Test
    void bundledCharacterDataBuildsWithinMovePools() throws IOException {
        Path movesPath = List.of(
                Path.of("data", "moves", "all_moves.json"),
                Path.of("..", "data", "moves", "all_moves.json"))
            .stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IOException("Could not locate bundled move data"));
        Path charactersPath = List.of(
                Path.of("data", "characters", "all_characters.json"),
                Path.of("..", "data", "characters", "all_characters.json"))
            .stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IOException("Could not locate bundled character data"));

        ObjectMapper mapper = new ObjectMapper();
        List<MoveData> moveData = mapper.readValue(
            movesPath.toFile(), new TypeReference<List<MoveData>>() {});
        List<CharacterData> characters = mapper.readValue(
            charactersPath.toFile(), new TypeReference<List<CharacterData>>() {});
        Map<String, Move> movesById = new HashMap<>();
        for (MoveData move : moveData) {
            movesById.put(move.id, move.toMove());
        }

        assertTrue(movesById.get("000000").isFreeMove());
        assertTrue(movesById.get("000001").isFreeMove());
        assertEquals(List.of("Ren Kurogane", "Mina Ishikawa", "Sora Aizawa", "Haruta Shigemo"),
            characters.stream().map(character -> character.name).toList());

        CharacterData haruta = characters.stream()
            .filter(character -> "Haruta Shigemo".equals(character.name))
            .findFirst()
            .orElseThrow();
        assertEquals("Miracles", haruta.innateTechniqueName);
        assertEquals(15, haruta.cursedTechniqueMastery);
        assertEquals(List.of("000001", "000002", "000003"), haruta.abilityIds);
        assertEquals(List.of("000001", "000003", "000002"), haruta.availableAbilityIds);

        for (CharacterData character : characters) {
            List<Move> knownMoves = new ArrayList<>();
            for (String moveId : character.moveIds) {
                Move move = movesById.get(moveId);
                assertNotNull(move, "Missing move " + moveId + " for " + character.name);
                knownMoves.add(move);
            }
            assertDoesNotThrow(() -> new SorcererCharacter(
                character.id, character.name, character.toCharacterStats(),
                character.innateTechniqueName, knownMoves
            ), character.name);
        }
    }

    @Test
    void bundledMoveMigrationAddsMissingMovesWithoutOverwritingPlayerMoves() throws IOException {
        Path savedMoves = Files.createTempFile("moves", ".json");
        try {
            Files.writeString(savedMoves, """
                [
                  { "id": "000000", "name": "Basic Strike", "description": "Player edit" },
                  { "id": "000001", "name": "Custom Move" }
                ]
                """);
            String bundled = """
                [
                  { "id": "000000", "name": "Basic Strike", "description": "Bundled default" },
                  { "id": "000001", "name": "Jab" }
                ]
                """;

            assertTrue(AppPaths.mergeBundledMoves(savedMoves,
                new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8))));

            List<MoveData> migrated = new ObjectMapper().readValue(
                savedMoves.toFile(), new TypeReference<List<MoveData>>() {});
            assertEquals(3, migrated.size());
            assertEquals("Player edit", migrated.get(0).description);
            assertTrue(migrated.get(0).isFreeMove);
            assertEquals("000002", migrated.get(2).id);
            assertEquals("Jab", migrated.get(2).name);
        } finally {
            Files.deleteIfExists(savedMoves);
        }
    }

    @Test
    void bundledCharacterMigrationAddsMissingCharactersWithoutOverwritingPlayerCharacters() throws IOException {
        Path savedCharacters = Files.createTempFile("characters", ".json");
        try {
            Files.writeString(savedCharacters, """
                [
                  { "id": "000000", "name": "Ren Kurogane", "description": "Player edit" },
                  { "id": "000001", "name": "Custom Fighter" }
                ]
                """);
            String bundled = """
                [
                  { "id": "000000", "name": "Ren Kurogane", "description": "Bundled default" },
                  { "id": "000001", "name": "Mina Ishikawa" },
                  { "id": "000002", "name": "Sora Aizawa" }
                ]
                """;

            assertTrue(AppPaths.mergeBundledCharacters(savedCharacters,
                new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8))));

            List<CharacterData> migrated = new ObjectMapper().readValue(
                savedCharacters.toFile(), new TypeReference<List<CharacterData>>() {});
            assertEquals(4, migrated.size());
            assertEquals("Player edit", migrated.get(0).description);
            assertEquals("Custom Fighter", migrated.get(1).name);
            assertEquals("000002", migrated.get(2).id);
            assertEquals("Mina Ishikawa", migrated.get(2).name);
            assertEquals("000003", migrated.get(3).id);
            assertEquals("Sora Aizawa", migrated.get(3).name);
        } finally {
            Files.deleteIfExists(savedCharacters);
        }
    }

    @Test
    void bundledCharacterMigrationCorrectsLegacySpritePaths() throws IOException {
        Path savedCharacters = Files.createTempFile("characters", ".json");
        try {
            Files.writeString(savedCharacters, """
                [
                  { "id": "000000", "name": "Ren Kurogane", "spriteAsset": "assets/characters/yuji_frontsprite.png" },
                  { "id": "000001", "name": "Legacy Test A", "spriteAsset": "assets/characters/test_a.png" },
                  { "id": "000002", "name": "Legacy Test B", "spriteAsset": "assets/sprites/characters/test_b.png" },
                  { "id": "000003", "name": "Legacy Test C", "spriteAsset": "assets\\\\characters\\\\test_c.png" },
                  { "id": "000004", "name": "Custom Fighter", "spriteAsset": "assets/custom/fighter.png" }
                ]
                """);
            String bundled = """
                [
                  { "id": "000000", "name": "Ren Kurogane" }
                ]
                """;

            assertTrue(AppPaths.mergeBundledCharacters(savedCharacters,
                new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8))));

            List<CharacterData> migrated = new ObjectMapper().readValue(
                savedCharacters.toFile(), new TypeReference<List<CharacterData>>() {});
            assertEquals("assets/sprites/characters/yuji_frontsprite.png", migrated.get(0).spriteAsset);
            assertEquals("assets/sprites/characters/yuji_frontsprite.png", migrated.get(1).spriteAsset);
            assertEquals("assets/sprites/characters/yuji_frontsprite.png", migrated.get(2).spriteAsset);
            assertEquals("assets/sprites/characters/megumi_frontsprite.png", migrated.get(3).spriteAsset);
            assertEquals("assets/custom/fighter.png", migrated.get(4).spriteAsset);
        } finally {
            Files.deleteIfExists(savedCharacters);
        }
    }

    @Test
    void bundledAbilityAndTechniqueMigrationPreservesPlayerEdits() throws IOException {
        Path savedAbilities = Files.createTempFile("abilities", ".json");
        Path savedTechniques = Files.createTempFile("techniques", ".json");
        try {
            Files.writeString(savedAbilities, """
                [
                  { "id": "000000", "name": "New Ability", "flavourText": "Player edit" },
                  { "id": "000001", "name": "Custom Ability" }
                ]
                """);
            String bundledAbilities = """
                [
                  { "id": "000000", "name": "New Ability", "flavourText": "Bundled default" },
                  { "id": "000001", "name": "Six Eyes" }
                ]
                """;

            assertTrue(AppPaths.mergeBundledAbilities(savedAbilities,
                new ByteArrayInputStream(bundledAbilities.getBytes(StandardCharsets.UTF_8))));

            List<Map<String, Object>> migratedAbilities = new ObjectMapper().readValue(
                savedAbilities.toFile(), new TypeReference<List<Map<String, Object>>>() {});
            assertEquals(3, migratedAbilities.size());
            assertEquals("Player edit", migratedAbilities.get(0).get("flavourText"));
            assertEquals("000002", migratedAbilities.get(2).get("id"));
            assertEquals("Six Eyes", migratedAbilities.get(2).get("name"));

            Files.writeString(savedTechniques, """
                [
                  { "id": "000000", "name": "Shrine", "description": "Player edit" },
                  { "id": "000001", "name": "Custom Technique" }
                ]
                """);
            String bundledTechniques = """
                [
                  { "id": "000000", "name": "Shrine", "description": "Bundled default" },
                  { "id": "000001", "name": "Limitless" }
                ]
                """;

            assertTrue(AppPaths.mergeBundledTechniques(savedTechniques,
                new ByteArrayInputStream(bundledTechniques.getBytes(StandardCharsets.UTF_8))));

            List<Map<String, Object>> migratedTechniques = new ObjectMapper().readValue(
                savedTechniques.toFile(), new TypeReference<List<Map<String, Object>>>() {});
            assertEquals(3, migratedTechniques.size());
            assertEquals("Player edit", migratedTechniques.get(0).get("description"));
            assertEquals("000002", migratedTechniques.get(2).get("id"));
            assertEquals("Limitless", migratedTechniques.get(2).get("name"));
        } finally {
            Files.deleteIfExists(savedAbilities);
            Files.deleteIfExists(savedTechniques);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void bundledTechniqueMigrationCarriesTreesAndCharacterNodeState() throws IOException {
        Path savedTechniques = Files.createTempFile("techniques", ".json");
        Path savedCharacters = Files.createTempFile("characters", ".json");
        try {
            Files.writeString(savedTechniques, """
                [
                  {
                    "id": "000004",
                    "name": "Miracles",
                    "description": "Player edit",
                    "skillTree": [
                      {
                        "id": "node-old",
                        "contentType": "MOVE",
                        "contentId": "old-move",
                        "x": 1,
                        "y": 2,
                        "prerequisites": []
                      }
                    ]
                  }
                ]
                """);
            String bundledTechniques = """
                [
                  {
                    "id": "000000",
                    "name": "Miracles",
                    "description": "Bundled description",
                    "skillTree": [
                      {
                        "id": "node-move",
                        "contentType": "MOVE",
                        "contentId": "base-move",
                        "x": 420.5,
                        "y": 85.25,
                        "prerequisites": [
                          { "type": "STAT", "stat": "strength", "minimum": 100 }
                        ]
                      },
                      {
                        "id": "node-ability",
                        "contentType": "ABILITY",
                        "contentId": "base-ability",
                        "x": 720,
                        "y": 85.25,
                        "prerequisites": [
                          {
                            "type": "NODE",
                            "nodeId": "node-move",
                            "attached": true
                          }
                        ]
                      }
                    ]
                  }
                ]
                """;
            Map<String, String> moveIds = Map.of("base-move", "local-move");
            Map<String, String> abilityIds = Map.of("base-ability", "local-ability");

            assertTrue(AppPaths.mergeBundledTechniques(
                savedTechniques,
                new ByteArrayInputStream(bundledTechniques.getBytes(StandardCharsets.UTF_8)),
                moveIds,
                abilityIds));

            List<Map<String, Object>> migratedTechniques = new ObjectMapper().readValue(
                savedTechniques.toFile(), new TypeReference<List<Map<String, Object>>>() {});
            Map<String, Object> migratedTechnique = migratedTechniques.get(0);
            assertEquals("Player edit", migratedTechnique.get("description"));
            List<Map<String, Object>> tree = (List<Map<String, Object>>) migratedTechnique.get("skillTree");
            assertEquals(2, tree.size());
            assertEquals("local-move", tree.get(0).get("contentId"));
            assertEquals(420.5, ((Number) tree.get(0).get("x")).doubleValue());
            assertEquals("local-ability", tree.get(1).get("contentId"));
            List<Map<String, Object>> prerequisites =
                (List<Map<String, Object>>) tree.get(1).get("prerequisites");
            assertEquals("node-move", prerequisites.get(0).get("nodeId"));
            assertEquals(Boolean.TRUE, prerequisites.get(0).get("attached"));

            Files.writeString(savedCharacters, """
                [
                  {
                    "id": "000004",
                    "name": "Haruta",
                    "description": "Player character edit",
                    "spriteAsset": "assets/custom/haruta.png",
                    "innateTechniqueName": "Old Technique",
                    "cursedTechniqueMastery": 0,
                    "moveIds": [ "old-move" ],
                    "abilityIds": [ "old-ability" ],
                    "availableAbilityIds": [ "old-ability" ]
                  }
                ]
                """);
            String bundledCharacters = """
                [
                  {
                    "id": "000000",
                    "name": "Haruta",
                    "innateTechniqueName": "Miracles",
                    "cursedTechniqueMastery": 15,
                    "moveIds": [ "base-move" ],
                    "abilityIds": [ "base-ability" ],
                    "availableAbilityIds": [ "base-ability" ]
                  }
                ]
                """;

            assertTrue(AppPaths.mergeBundledCharacters(
                savedCharacters,
                new ByteArrayInputStream(bundledCharacters.getBytes(StandardCharsets.UTF_8)),
                moveIds,
                abilityIds));

            List<Map<String, Object>> migratedCharacters = new ObjectMapper().readValue(
                savedCharacters.toFile(), new TypeReference<List<Map<String, Object>>>() {});
            Map<String, Object> migratedCharacter = migratedCharacters.get(0);
            assertEquals("000004", migratedCharacter.get("id"));
            assertEquals("Player character edit", migratedCharacter.get("description"));
            assertEquals("assets/custom/haruta.png", migratedCharacter.get("spriteAsset"));
            assertEquals("Miracles", migratedCharacter.get("innateTechniqueName"));
            assertEquals(15, migratedCharacter.get("cursedTechniqueMastery"));
            assertEquals(List.of("local-move"), migratedCharacter.get("moveIds"));
            assertEquals(List.of("local-ability"), migratedCharacter.get("abilityIds"));
            assertEquals(List.of("local-ability"), migratedCharacter.get("availableAbilityIds"));

            String bundledWithoutTechnique = """
                [ {
                  "id": "000000",
                  "name": "Haruta",
                  "cursedTechniqueMastery": 0,
                  "moveIds": [ "base-move" ],
                  "abilityIds": [ ]
                } ]
                """;
            assertTrue(AppPaths.mergeBundledCharacters(
                savedCharacters,
                new ByteArrayInputStream(bundledWithoutTechnique.getBytes(StandardCharsets.UTF_8)),
                moveIds,
                abilityIds));
            migratedCharacters = new ObjectMapper().readValue(
                savedCharacters.toFile(), new TypeReference<List<Map<String, Object>>>() {});
            migratedCharacter = migratedCharacters.get(0);
            assertFalse(migratedCharacter.containsKey("innateTechniqueName"));
            assertFalse(migratedCharacter.containsKey("availableAbilityIds"));
            assertEquals(0, migratedCharacter.get("cursedTechniqueMastery"));
            assertEquals(List.of("local-move"), migratedCharacter.get("moveIds"));
            assertEquals(List.of(), migratedCharacter.get("abilityIds"));
        } finally {
            Files.deleteIfExists(savedTechniques);
            Files.deleteIfExists(savedCharacters);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void releaseSeedRemapsBundledTechniqueStateToLocalRepositoryIds() throws Exception {
        Path resourceRoot = Files.createTempDirectory("bundled-data");
        Path profileRoot = Files.createTempDirectory("player-data");
        String previousDataRoot = System.getProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY);
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        URLClassLoader resourceLoader = new URLClassLoader(
            new java.net.URL[] { resourceRoot.toUri().toURL() }, null);
        try {
            writeJson(resourceRoot, "data/moves/all_moves.json", """
                [ { "id": "000000", "name": "Tree Move" } ]
                """);
            writeJson(resourceRoot, "data/abilities/all_abilities.json", """
                [ { "id": "000000", "name": "Tree Ability" } ]
                """);
            writeJson(resourceRoot, "data/techniques/all_techniques.json", """
                [ {
                  "id": "000000",
                  "name": "Miracles",
                  "skillTree": [ {
                    "id": "node-move",
                    "contentType": "MOVE",
                    "contentId": "000000",
                    "x": 300,
                    "y": 120,
                    "prerequisites": []
                  }, {
                    "id": "node-ability",
                    "contentType": "ABILITY",
                    "contentId": "000000",
                    "x": 600,
                    "y": 120,
                    "prerequisites": [ {
                      "type": "NODE",
                      "nodeId": "node-move",
                      "attached": true
                    } ]
                  } ]
                } ]
                """);
            writeJson(resourceRoot, "data/characters/all_characters.json", """
                [ {
                  "id": "000000",
                  "name": "Haruta",
                  "innateTechniqueName": "Miracles",
                  "cursedTechniqueMastery": 15,
                  "moveIds": [ "000000" ],
                  "abilityIds": [ "000000" ],
                  "availableAbilityIds": [ "000000" ]
                } ]
                """);

            writeJson(profileRoot, "data/moves/all_moves.json", """
                [
                  { "id": "000000", "name": "Custom Move" },
                  { "id": "000001", "name": "Tree Move" }
                ]
                """);
            writeJson(profileRoot, "data/abilities/all_abilities.json", """
                [
                  { "id": "000000", "name": "Custom Ability" },
                  { "id": "000001", "name": "Tree Ability" }
                ]
                """);
            writeJson(profileRoot, "data/techniques/all_techniques.json", """
                [ { "id": "000000", "name": "Miracles", "skillTree": [] } ]
                """);
            writeJson(profileRoot, "data/characters/all_characters.json", """
                [ {
                  "id": "000000",
                  "name": "Haruta",
                  "description": "Player edit",
                  "cursedTechniqueMastery": 0,
                  "moveIds": [ "000000" ],
                  "abilityIds": [ "000000" ],
                  "availableAbilityIds": [ "000000" ]
                } ]
                """);

            System.setProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY, profileRoot.toString());
            Thread.currentThread().setContextClassLoader(resourceLoader);
            AppPaths.seedDataIfAbsent();

            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> techniques = mapper.readValue(
                profileRoot.resolve("data/techniques/all_techniques.json").toFile(),
                new TypeReference<List<Map<String, Object>>>() {});
            List<Map<String, Object>> tree =
                (List<Map<String, Object>>) techniques.get(0).get("skillTree");
            assertEquals("000001", tree.get(0).get("contentId"));
            assertEquals("000001", tree.get(1).get("contentId"));
            List<Map<String, Object>> prerequisites =
                (List<Map<String, Object>>) tree.get(1).get("prerequisites");
            assertEquals(Boolean.TRUE, prerequisites.get(0).get("attached"));

            List<Map<String, Object>> characters = mapper.readValue(
                profileRoot.resolve("data/characters/all_characters.json").toFile(),
                new TypeReference<List<Map<String, Object>>>() {});
            Map<String, Object> haruta = characters.get(0);
            assertEquals("Player edit", haruta.get("description"));
            assertEquals("Miracles", haruta.get("innateTechniqueName"));
            assertEquals(15, haruta.get("cursedTechniqueMastery"));
            assertEquals(List.of("000001"), haruta.get("moveIds"));
            assertEquals(List.of("000001"), haruta.get("abilityIds"));
            assertEquals(List.of("000001"), haruta.get("availableAbilityIds"));
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            if (previousDataRoot == null) {
                System.clearProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY);
            } else {
                System.setProperty(AppPaths.DATA_ROOT_SYSTEM_PROPERTY, previousDataRoot);
            }
            resourceLoader.close();
            AppPaths.deleteRecursively(resourceRoot);
            AppPaths.deleteRecursively(profileRoot);
        }
    }

    @Test
    void blockDurationStartsAtFireTickAndUsesApCostWhenDurationIsZero() {
        Move attack = new Move.Builder("TEST_ATTACK")
            .name("Test Attack")
            .category(MoveCategory.PHYSICAL)
            .basePower(50)
            .apCost(10)
            .unleashPoint(1)
            .build();
        Move block = new Move.Builder("TEST_BLOCK")
            .name("Test Block")
            .category(MoveCategory.DEFENSIVE)
            .apCost(10)
            .unleashPoint(3)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .blockDuration(0)
            .blockAffectedTags(List.of("PHYSICAL"))
            .blockDamageReduction(50)
            .build();

        Timeline timeline = new Timeline(30);
        timeline.placeAt(block, 1, 0);

        assertNull(timeline.activeBlockAt(2, attack));
        assertNotNull(timeline.activeBlockAt(3, attack));
        assertNotNull(timeline.activeBlockAt(12, attack));
        assertNull(timeline.activeBlockAt(13, attack));
    }

    @Test
    void blockAffectedTagsFilterIncomingMoveTags() {
        Move physicalAttack = new Move.Builder("PHYSICAL_TEST")
            .name("Physical Test")
            .category(MoveCategory.PHYSICAL)
            .basePower(50)
            .build();
        Move innateAttack = new Move.Builder("INNATE_TEST")
            .name("Innate Test")
            .category(MoveCategory.INNATE_TECHNIQUE)
            .requiredTechniqueId("SHRINE")                                  // technique-tag invariant
            .prerequisites(java.util.Map.of("cursedtechniquemastery", 0))   // technique-tag invariant
            .basePower(50)
            .build();
        Move physicalBlock = new Move.Builder("PHYSICAL_BLOCK")
            .name("Physical Block")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .blockAffectedTags(List.of("PHYSICAL"))
            .blockDamageReduction(50)
            .build();

        Timeline timeline = new Timeline(30);
        timeline.placeAt(physicalBlock, 1, 0);

        assertNotNull(timeline.activeBlockAt(10, physicalAttack));
        assertNull(timeline.activeBlockAt(10, innateAttack));
    }

    /**
     * Block coverage is subset-direction: a block fires iff it covers every
     * damage tag the incoming attack uses (attack tags ⊆ block tags).
     *
     * Example 1: a PHYSICAL-only block does NOT stop a PHYSICAL+CURSED_ENERGY
     * attack — the CE component slips through.
     */
    @Test
    void physicalBlockDoesNotCoverHybridAttack() {
        Move hybridAttack = new Move.Builder("HYBRID_ATK")
            .name("Hybrid Attack")
            .category(MoveCategory.PHYSICAL_CURSED_ENERGY)
            .basePower(50)
            .apCost(10).unleashPoint(1)
            .build();
        Move physicalBlock = new Move.Builder("PHYS_ONLY_BLOCK")
            .name("Physical Only Block")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .apCost(10).unleashPoint(1)
            .blockAffectedTags(List.of("PHYSICAL"))
            .blockDamageReduction(50)
            .build();

        Timeline timeline = new Timeline(30);
        timeline.placeAt(physicalBlock, 1, 0);

        assertNull(timeline.activeBlockAt(5, hybridAttack),
            "A [PHYSICAL]-only block must NOT cover a PHYSICAL+CURSED_ENERGY attack.");
    }

    /**
     * Example 2: a [PHYSICAL, CURSED_ENERGY] block DOES stop a pure-PHYSICAL
     * attack — the block's coverage is a superset of the attack's tags.
     */
    @Test
    void dualTagBlockCoversPhysicalSubset() {
        Move physicalAttack = new Move.Builder("PURE_PHYS")
            .name("Pure Physical")
            .category(MoveCategory.PHYSICAL)
            .basePower(50)
            .apCost(10).unleashPoint(1)
            .build();
        Move dualBlock = new Move.Builder("DUAL_BLOCK")
            .name("Dual Block")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .apCost(10).unleashPoint(1)
            .blockAffectedTags(List.of("PHYSICAL", "CURSED_ENERGY"))
            .blockDamageReduction(50)
            .build();

        Timeline timeline = new Timeline(30);
        timeline.placeAt(dualBlock, 1, 0);

        assertNotNull(timeline.activeBlockAt(5, physicalAttack),
            "A [PHYSICAL, CURSED_ENERGY] block MUST cover a pure-PHYSICAL attack.");
    }

    /**
     * Raw CE-only attacks now resolve to the dedicated CURSED_ENERGY category
     * (previously degraded to UTILITY), and compute Power via the CE base formula.
     */
    @Test
    void cursedEnergyOnlyIsADedicatedDamagingCategory() {
        MoveData d = new MoveData();
        d.tags = List.of("CURSED_ENERGY", "ATTACK");
        assertEquals(MoveCategory.CURSED_ENERGY, d.derivedCategory(),
            "A CE-only tag set must derive to the CURSED_ENERGY category.");

        // A CE-only move (no PHYSICAL tag) is a Jujutsu Art — slots granted
        // by Jujutsu Skill, not Combat Ability.
        assertEquals(MovePool.JUJUTSU_ARTS, d.derivedPool(),
            "A CE-only (no PHYSICAL) move must be a Jujutsu Art.");

        Move ceAttack = new Move.Builder("CE_TEST")
            .name("CE Test")
            .category(MoveCategory.CURSED_ENERGY)
            .basePower(50)
            .apCost(10).unleashPoint(1)
            .build();
        assertTrue(ceAttack.hasTag("CURSED_ENERGY"));
        assertFalse(ceAttack.isBlackFlashEligible(),
            "A CE-only move has no physical contact and cannot Black Flash.");

        // Power uses the 3:2:1 CE base formula: (OUT*3 + RES*2 + EFF)/6.
        CharacterStats stats = new CharacterStats.Builder()
            .cursedEnergyOutput(80).cursedEnergyReserves(80).cursedEnergyEfficiency(80).build();
        assertEquals((80 * 3 + 80 * 2 + 80) / 6,
            PowerCalculator.compute(MoveCategory.CURSED_ENERGY, stats));
    }

    @Test
    void blockReductionAppliesBeforeDefenseAndScaleRoll() {
        Move attack = new Move.Builder("TEST_ATTACK")
            .name("Test Attack")
            .category(MoveCategory.PHYSICAL)
            .basePower(100)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();
        Move block = new Move.Builder("TEST_BLOCK")
            .name("Test Block")
            .category(MoveCategory.DEFENSIVE)
            .defenseType(DefenseType.PERCENTAGE_BLOCK)
            .blockDamageReduction(50)
            .neverMiss(true)
            .apCost(10)
            .unleashPoint(1)
            .build();

        CharacterStats stats = new CharacterStats.Builder().build();
        Character attackerChar = new SorcererCharacter("A", "Attacker", stats, null, List.of(attack));
        Character defenderChar = new SorcererCharacter("D", "Defender", stats, null, List.of(block));
        BattleCombatant attacker = new BattleCombatant(attackerChar);
        BattleCombatant defender = new BattleCombatant(defenderChar);
        Timeline defenderTimeline = new Timeline(30);
        defenderTimeline.placeAt(block, 1, 0);
        defender.setTimeline(defenderTimeline);

        DamageCalculator.DamageResult result = DamageCalculator.resolve(
            attacker, defender, attack, 1, new FixedRandom(0.0), 1
        );

        // Baseline stats (all 80), full CE. Defense caps CE reinforcement by Output:
        //   ceReinf = min(80, 80*0.5=40) = 40; DEF = (40*6 + 80*2)/6 = 67
        assertEquals(25, result.getFinalDamage());
    }

    private static void writeJson(Path root, String relativePath, String contents) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    private static final class FixedRandom extends Random {
        private final double value;

        private FixedRandom(double value) {
            this.value = value;
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }
}
