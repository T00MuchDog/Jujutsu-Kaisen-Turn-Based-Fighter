package com.jjktbf.server.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityEffectData;
import com.jjktbf.model.character.AbilityEffectType;
import com.jjktbf.model.character.AbilityConditionRuleData;
import com.jjktbf.model.character.AbilityConditionData;
import com.jjktbf.model.character.AbilityConditionType;
import com.jjktbf.model.character.AbilityResolver;
import com.jjktbf.model.character.Character;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterType;
import com.jjktbf.model.character.Equipment;
import com.jjktbf.model.character.StatKey;
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.character.coded.NewShadowStyleAbility;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.SkillTreeNodeData;
import com.jjktbf.model.technique.TechniqueSkillTree;
import com.jjktbf.model.weapon.CursedToolData;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/** Server-owned canonical content loaded only from immutable classpath resources. */
public final class ContentCatalog {
    public static final String MOVES_RESOURCE = "/data/moves/all_moves.json";
    public static final String CHARACTERS_RESOURCE = "/data/characters/all_characters.json";
    public static final String ABILITIES_RESOURCE = "/data/abilities/all_abilities.json";
    public static final String TECHNIQUES_RESOURCE = "/data/techniques/all_techniques.json";
    public static final String CURSED_TOOLS_RESOURCE = "/data/tools/all_tools.json";

    private final Map<String, Character> charactersById;
    private final List<CharacterSummary> characterSummaries;
    private final Set<String> selectableCharacterIds;

    private ContentCatalog(
        Map<String, Character> charactersById,
        List<CharacterSummary> characterSummaries
    ) {
        this.charactersById = Collections.unmodifiableMap(
            new LinkedHashMap<>(charactersById));
        this.characterSummaries = List.copyOf(characterSummaries);
        LinkedHashSet<String> selectableIds = new LinkedHashSet<>();
        characterSummaries.stream()
            .map(CharacterSummary::characterId)
            .forEach(selectableIds::add);
        this.selectableCharacterIds = Collections.unmodifiableSet(selectableIds);
    }

    public static ContentCatalog load() {
        ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        List<MoveData> moves = read(mapper, MOVES_RESOURCE, new TypeReference<>() { });
        List<CharacterData> characters = read(
            mapper, CHARACTERS_RESOURCE, new TypeReference<>() { });
        List<AbilityData> abilities = read(
            mapper, ABILITIES_RESOURCE, new TypeReference<>() { });
        List<InnateTechniqueData> techniques = read(
            mapper, TECHNIQUES_RESOURCE, new TypeReference<>() { });
        List<CursedToolData> cursedTools = read(
            mapper, CURSED_TOOLS_RESOURCE, new TypeReference<>() { });
        return build(moves, characters, abilities, techniques, cursedTools);
    }

    /** Creates a minimal catalog for focused service tests and future embedding. */
    public static ContentCatalog of(List<? extends Character> characters) {
        return of(characters, Map.of());
    }

    static ContentCatalog of(
        List<? extends Character> characters,
        Map<String, Boolean> directlySelectableOverrides
    ) {
        if (characters == null || characters.isEmpty()) {
            throw new IllegalArgumentException("Canonical character list must not be empty");
        }
        Map<String, Boolean> overrides = directlySelectableOverrides == null
            ? Map.of() : directlySelectableOverrides;
        Map<String, Character> byId = new LinkedHashMap<>();
        List<CharacterSummary> summaries = new ArrayList<>();
        for (Character character : characters) {
            Objects.requireNonNull(character, "character");
            requireIdentifier(character.getId(), "character ID");
            requireText(character.getName(), "character name");
            if (byId.putIfAbsent(character.getId(), character) != null) {
                throw new IllegalArgumentException(
                    "Duplicate canonical character ID: " + character.getId());
            }
            Boolean override = overrides.get(character.getId());
            boolean selectable = override != null
                ? override : defaultSelectable(character);
            if (selectable) {
                summaries.add(new CharacterSummary(character.getId(), character.getName(), ""));
            }
        }
        return new ContentCatalog(byId, summaries);
    }

    /**
     * Summaries of every directly-selectable canonical definition. Non-selectable
     * definitions (e.g. shikigami intended only for summoning) are hidden from
     * fighter rosters, multiplayer summaries, and challenge creation/acceptance.
     */
    public List<CharacterSummary> characterSummaries() {
        return characterSummaries;
    }

    /** Every canonical definition (including non-selectable ones), by id. */
    public Optional<Character> findCharacter(String characterId) {
        if (characterId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(charactersById.get(characterId));
    }

    /**
     * Resolve a directly-selectable canonical character. Hidden (non-selectable)
     * definitions — e.g. shikigami that exist only to be summoned — are absent,
     * so a crafted request that names a hidden character cannot smuggle it into
     * a match.
     */
    public Optional<Character> findSelectableCharacter(String characterId) {
        if (characterId == null || !selectableCharacterIds.contains(characterId)) {
            return Optional.empty();
        }
        return findCharacter(characterId);
    }

    private static boolean defaultSelectable(Character character) {
        if (character == null) return false;
        return character.getType() != CharacterType.SHIKIGAMI;
    }

    private static ContentCatalog build(
        List<MoveData> moveDefinitions,
        List<CharacterData> characterDefinitions,
        List<AbilityData> abilityDefinitions,
        List<InnateTechniqueData> techniqueDefinitions,
        List<CursedToolData> cursedToolDefinitions
    ) {
        requireNonEmpty(moveDefinitions, MOVES_RESOURCE);
        requireNonEmpty(characterDefinitions, CHARACTERS_RESOURCE);
        if (abilityDefinitions == null) {
            throw invalid(ABILITIES_RESOURCE, "top-level JSON value must be an array");
        }
        if (techniqueDefinitions == null) {
            throw invalid(TECHNIQUES_RESOURCE, "top-level JSON value must be an array");
        }
        if (cursedToolDefinitions == null) {
            throw invalid(CURSED_TOOLS_RESOURCE, "top-level JSON value must be an array");
        }
        Map<String, CursedToolData> toolsById = new LinkedHashMap<>();
        for (CursedToolData tool : cursedToolDefinitions) {
            if (tool == null) continue;
            requireIdentifier(tool.id, "cursed tool ID");
            requireText(tool.name, "cursed tool name for " + tool.id);
            try {
                tool.effectiveWeaponType();
            } catch (IllegalArgumentException exception) {
                throw invalid(CURSED_TOOLS_RESOURCE,
                    "cursed tool " + tool.id + " has an invalid weapon type", exception);
            }
            if (toolsById.put(tool.id, tool) != null) {
                throw invalid(CURSED_TOOLS_RESOURCE, "duplicate cursed tool ID " + tool.id);
            }
        }
        techniqueDefinitions.forEach(technique -> TechniqueSkillTree.synchronize(
            technique, moveDefinitions, abilityDefinitions));

        Map<String, Move> movesById = new LinkedHashMap<>();
        Map<String, MoveData> moveDataById = new LinkedHashMap<>();
        Map<String, List<MoveEffectData>> codedEffectsByMoveId =
            new LinkedHashMap<>();
        for (MoveData definition : moveDefinitions) {
            if (definition == null) {
                throw invalid(MOVES_RESOURCE, "contains a null move definition");
            }
            requireIdentifier(definition.id, "move ID");
            requireText(definition.name, "move name for " + definition.id);
            if (definition.requiredCursedToolId != null
                && !definition.requiredCursedToolId.isBlank()
                && !toolsById.containsKey(definition.requiredCursedToolId)) {
                throw invalid(MOVES_RESOURCE, "move " + definition.id
                    + " references unknown cursed tool " + definition.requiredCursedToolId);
            }
            definition.migrateLegacyEffects();
            // Coded bindings now live on effect rows (self or on-hit), not on the
            // move. Validate every coded effect row against the registry allow-list.
            // Keep these rows because legacy on-hit migration clears the DTO field.
            List<MoveEffectData> codedEffects = codedEffectRows(definition);
            codedEffectsByMoveId.put(definition.id, codedEffects);
            for (MoveEffectData effect : codedEffects) {
                if (!CodedAbilityRegistry.supportsEffect(
                    effect.codedAbilityKey,
                    effect.codedAction,
                    effect.codedTarget,
                    effect.codedStackCount)) {
                    throw invalid(MOVES_RESOURCE, "invalid coded action on move " + definition.id);
                }
            }
            try {
                Move move = definition.toMove();
                if (movesById.putIfAbsent(definition.id, move) != null) {
                    throw invalid(MOVES_RESOURCE, "duplicate move ID " + definition.id);
                }
                moveDataById.put(definition.id, definition);
            } catch (IllegalArgumentException exception) {
                throw invalid(MOVES_RESOURCE,
                    "invalid move " + definition.id + ": " + exception.getMessage(), exception);
            }
        }
        for (MoveData definition : moveDefinitions) {
            for (MoveEffectData effect : codedEffectsByMoveId.get(definition.id)) {
                if (NewShadowStyleAbility.KEY.equalsIgnoreCase(effect.codedAbilityKey)
                    && NewShadowStyleAbility.ACTIVATE_SIMPLE_DOMAIN.equalsIgnoreCase(effect.codedAction)
                        && !NewShadowStyleAbility.isValidReactionMove(
                            movesById.get(effect.codedTarget))) {
                        throw invalid(MOVES_RESOURCE, "Simple Domain move " + definition.id
                            + " must reference a physical, reinforced, stunning melee KATANA move");
                    }
            }
            for (MoveEffectData effect : definition.effects == null
                ? List.<MoveEffectData>of() : definition.effects) {
                if (effect == null) continue;
                String missingMove = missingConditionMove(
                    effect.condition, movesById.keySet());
                if (missingMove != null) {
                    throw invalid(MOVES_RESOURCE, "move " + definition.id
                        + " effect condition references unknown move " + missingMove);
                }
                missingMove = missingConditionMove(
                    effect.returnCondition, movesById.keySet());
                if (missingMove != null) {
                    throw invalid(MOVES_RESOURCE, "move " + definition.id
                        + " transformation return condition references unknown move "
                        + missingMove);
                }
            }
        }

        Set<String> abilityIds = new LinkedHashSet<>();
        for (AbilityData definition : abilityDefinitions) {
            if (definition == null) {
                throw invalid(ABILITIES_RESOURCE, "contains a null ability definition");
            }
            requireIdentifier(definition.id, "ability ID");
            requireText(definition.name, "ability name for " + definition.id);
            if ("CURSED_TOOL".equalsIgnoreCase(definition.sourceType)
                && (definition.sourceValue == null
                    || !toolsById.containsKey(definition.sourceValue))) {
                throw invalid(ABILITIES_RESOURCE, "ability " + definition.id
                    + " references an unknown cursed tool");
            }
            definition.migrateActivationData();
            String effectIdError = AbilityConditionRuleData.effectIdValidationError(
                definition.effects);
            if (effectIdError != null) {
                throw invalid(ABILITIES_RESOURCE,
                    "invalid effects on ability " + definition.id + ": " + effectIdError);
            }
            for (AbilityEffectData effect : definition.effects == null
                ? List.<AbilityEffectData>of() : definition.effects) {
                if (effect != null && effect.isCoded()
                    && !CodedAbilityRegistry.supportsAbilityEffect(
                        effect.codedAbilityKey, effect.codedFeature)) {
                    throw invalid(ABILITIES_RESOURCE,
                        "invalid coded effect on ability " + definition.id);
                }
                if (effect != null) {
                    String missingMove = missingConditionMove(
                        effect.returnCondition, movesById.keySet());
                    if (missingMove != null) {
                        throw invalid(ABILITIES_RESOURCE, "ability " + definition.id
                            + " transformation return condition references unknown move "
                            + missingMove);
                    }
                }
            }
            if (definition.isActive()) {
                String conditionError = AbilityConditionRuleData.validationError(
                    definition.activationConditions, definition.effects);
                if (conditionError != null) {
                    throw invalid(ABILITIES_RESOURCE,
                        "invalid conditions on ability " + definition.id + ": " + conditionError);
                }
                for (AbilityConditionRuleData rule : definition.activationConditions) {
                    String missingMove = missingConditionMove(rule.condition, movesById.keySet());
                    if (missingMove != null) {
                        throw invalid(ABILITIES_RESOURCE,
                            "ability " + definition.id
                                + " condition references unknown move " + missingMove);
                    }
                }
            }
            if (!abilityIds.add(definition.id)) {
                throw invalid(ABILITIES_RESOURCE, "duplicate ability ID " + definition.id);
            }
        }

        Map<String, Character> charactersById = new LinkedHashMap<>();
        List<CharacterSummary> summaries = new ArrayList<>();
        for (CharacterData definition : characterDefinitions) {
            if (definition == null) {
                throw invalid(CHARACTERS_RESOURCE, "contains a null character definition");
            }
            requireIdentifier(definition.id, "character ID");
            requireText(definition.name, "character name for " + definition.id);
            if (charactersById.containsKey(definition.id)) {
                throw invalid(CHARACTERS_RESOURCE,
                    "duplicate character ID " + definition.id);
            }
            // Validate the stored type up front so a typo fails loudly here
            // rather than being silently downgraded at construction time.
            try {
                definition.effectiveType();
            } catch (IllegalArgumentException ex) {
                throw invalid(CHARACTERS_RESOURCE,
                    "character " + definition.id + " has an invalid type: " + ex.getMessage(),
                    ex);
            }
            if (definition.moveIds == null) {
                throw invalid(CHARACTERS_RESOURCE,
                    "character " + definition.id + " has no moveIds array");
            }
            verifyReferences(definition.availableMoveIds, movesById.keySet(),
                "available move", definition.id);
            verifyReferences(definition.abilityIds, abilityIds, "ability", definition.id);
            verifyReferences(definition.availableAbilityIds, abilityIds,
                "available ability", definition.id);
            verifyReferences(definition.equippedCursedToolIds, toolsById.keySet(),
                "equipped cursed tool", definition.id);
            Equipment prerequisiteEquipment;
            try {
                prerequisiteEquipment = Equipment.resolve(
                    definition.equippedWeaponTypes,
                    definition.equippedCursedToolIds,
                    cursedToolDefinitions,
                    new ArrayList<>(movesById.values()));
            } catch (IllegalArgumentException exception) {
                throw invalid(CHARACTERS_RESOURCE,
                    "invalid equipment for character " + definition.id + ": "
                    + exception.getMessage(), exception);
            }
            BiPredicate<SkillTreeNodeData, StatKey> prerequisiteWaiver = (node, stat) -> {
                if (node == null || stat == null || !stat.isJujutsuPrerequisite()
                    || !SkillTreeNodeData.MOVE.equalsIgnoreCase(node.contentType)) {
                    return false;
                }
                MoveData move = moveDataById.get(node.contentId);
                return move != null && prerequisiteEquipment.coversWeaponTags(move.weaponTags());
            };
            Equipment equipment = prerequisiteEquipment.filterGrantedMoves(
                move -> TechniqueSkillTree.allowsMove(
                    techniqueDefinitions, move.getRequiredTechniqueId(), move.getId(), definition,
                    prerequisiteWaiver));
            LinkedHashSet<String> resolvedMoveIds = new LinkedHashSet<>(definition.moveIds);
            List<Move> moves = new ArrayList<>();
            for (String moveId : resolvedMoveIds) {
                Move move = movesById.get(moveId);
                if (move == null) {
                    throw invalid(CHARACTERS_RESOURCE,
                        "character " + definition.id + " references unknown move " + moveId);
                }
                if (definition.moveIds.contains(moveId)) {
                    MoveData moveData = moveDataById.get(moveId);
                    if (moveData != null && !TechniqueSkillTree.allowsMove(
                        techniqueDefinitions, moveData.requiredTechniqueId,
                        moveId, definition, prerequisiteWaiver)) {
                        throw invalid(CHARACTERS_RESOURCE,
                        "character " + definition.id
                                + " does not meet technique-tree prerequisites for move " + moveId);
                    }
                }
                moves.add(move);
            }
            CharacterData.ResolvedCharacter resolvedContent;
            try {
                resolvedContent = definition.resolveEquipmentContent(
                    definition.toCharacterStats(), moves, equipment,
                    learnedToolMoveIds -> AbilityResolver.resolve(
                        definition, abilityDefinitions, movesById::containsKey,
                        techniqueDefinitions, learnedToolMoveIds,
                        equipment.grantedMoveIds()));
            } catch (IllegalArgumentException exception) {
                throw invalid(CHARACTERS_RESOURCE,
                    "invalid character " + definition.id + ": " + exception.getMessage(),
                    exception);
            }
            AbilityResolver.Result resolved = resolvedContent.abilities();
            try {
                definition.validateStatAllocationMinimums(resolved);
                definition.validateStatAllocationMaximums(resolved);
            } catch (IllegalArgumentException exception) {
                throw invalid(CHARACTERS_RESOURCE,
                    "character " + definition.id + " violates an ability allocation bound: "
                        + exception.getMessage(), exception);
            }
            if (definition.abilityIds != null) {
                for (String abilityId : definition.abilityIds) {
                    if (!resolved.containsAbility(abilityId)) {
                        throw invalid(CHARACTERS_RESOURCE,
                            "character " + definition.id
                                + " assigns unavailable ability " + abilityId);
                    }
                }
            }
            for (String moveId : definition.moveIds) {
                Move move = movesById.get(moveId);
                if (move != null && move.mustBeGranted()
                    && !resolved.availableMoveIds().contains(moveId)
                    && !equipment.grantedMoveIds().contains(moveId)) {
                    throw invalid(CHARACTERS_RESOURCE,
                        "character " + definition.id
                            + " learns unavailable grant-only move " + moveId);
                }
            }
            Character character = resolvedContent.character();
            charactersById.put(definition.id, character);
            // Only directly-selectable definitions appear in fighter rosters
            // / multiplayer summaries / challenge create+accept. Hidden
            // definitions (e.g. summon-only shikigami) remain resolvable via
            // findCharacter() but never via characterSummaries().
            if (definition.effectiveSelectable()) {
                summaries.add(new CharacterSummary(
                    definition.id,
                    definition.name,
                    Objects.requireNonNullElse(definition.description, "")
                ));
            }
        }

        validateSummonReferences(moveDefinitions, abilityDefinitions, charactersById);
        return new ContentCatalog(charactersById, summaries);
    }

    static void validateSummonReferences(
        List<MoveData> moves,
        List<AbilityData> abilities,
        Map<String, ? extends Character> charactersById
    ) {
        for (MoveData definition : moves == null ? List.<MoveData>of() : moves) {
            if (definition == null) continue;
            if (definition.summonCharacterId != null
                && !definition.summonCharacterId.isBlank()) {
                verifySummonReference(definition.summonCharacterId, charactersById,
                    MOVES_RESOURCE, "move " + definition.id);
            }
            if (definition.effects == null) continue;
            for (com.jjktbf.model.move.MoveEffectData effect : definition.effects) {
                if (effect == null) continue;
                AbilityEffectType effectType;
                try { effectType = AbilityEffectType.fromName(effect.type); }
                catch (IllegalArgumentException ignored) { continue; }
                if (!effectType.uses(
                    com.jjktbf.model.character.AbilityEffectParameter.CHARACTER_ID)) continue;
                if (effect.characterId == null || effect.characterId.isBlank()) {
                    throw invalid(MOVES_RESOURCE,
                        "move " + definition.id + " has no character target");
                }
                if (effectType == AbilityEffectType.TRANSFORM_CHARACTER) {
                    verifyCharacterReference(effect.characterId, charactersById,
                        MOVES_RESOURCE, "move " + definition.id);
                } else {
                    verifySummonReference(effect.characterId, charactersById,
                        MOVES_RESOURCE, "move " + definition.id);
                }
            }
        }
        for (AbilityData definition : abilities == null ? List.<AbilityData>of() : abilities) {
            if (definition == null || definition.effects == null) continue;
            for (AbilityEffectData effect : definition.effects) {
                if (effect == null) continue;
                AbilityEffectType effectType;
                try { effectType = AbilityEffectType.fromName(effect.type); }
                catch (IllegalArgumentException ignored) { continue; }
                boolean summonEffect = effectType == AbilityEffectType.SUMMON_CHARACTER;
                if (summonEffect && (effect.characterId == null
                    || effect.characterId.isBlank())) {
                    throw invalid(ABILITIES_RESOURCE,
                        "ability " + definition.id + " has no summon target");
                }
                if (effect.characterId == null || effect.characterId.isBlank()) continue;
                if (effectType == AbilityEffectType.TRANSFORM_CHARACTER) {
                    verifyCharacterReference(effect.characterId, charactersById,
                        ABILITIES_RESOURCE, "ability " + definition.id);
                } else if (summonEffect) {
                    verifySummonReference(effect.characterId, charactersById,
                        ABILITIES_RESOURCE, "ability " + definition.id);
                }
            }
        }
    }

    private static void verifySummonReference(
        String characterId,
        Map<String, ? extends Character> charactersById,
        String resource,
        String context
    ) {
        Character target = charactersById == null ? null : charactersById.get(characterId);
        if (target == null) {
            throw invalid(resource, context + " summons unknown character " + characterId);
        }
        if (target.getType() != CharacterType.SHIKIGAMI) {
            throw invalid(resource,
                context + " summons non-shikigami character " + characterId);
        }
    }

    private static void verifyCharacterReference(
        String characterId,
        Map<String, ? extends Character> charactersById,
        String resource,
        String context
    ) {
        if (charactersById == null || !charactersById.containsKey(characterId)) {
            throw invalid(resource,
                context + " transforms into unknown character " + characterId);
        }
    }

    private static String missingConditionMove(
        AbilityConditionData condition,
        Set<String> moveIds
    ) {
        if (condition == null) return null;
        if (AbilityConditionType.MOVE_USED.name().equalsIgnoreCase(condition.type)
            && !moveIds.contains(condition.moveId)) {
            return condition.moveId;
        }
        if (condition.children != null) {
            for (AbilityConditionData child : condition.children) {
                String missing = missingConditionMove(child, moveIds);
                if (missing != null) return missing;
            }
        }
        return null;
    }

    private static void verifyReferences(
        List<String> references,
        Set<String> knownIds,
        String referenceType,
        String characterId
    ) {
        if (references == null) {
            return;
        }
        for (String reference : references) {
            if (!knownIds.contains(reference)) {
                throw invalid(CHARACTERS_RESOURCE,
                    "character " + characterId + " references unknown "
                        + referenceType + " " + reference);
            }
        }
    }

    private static <T> List<T> read(
        ObjectMapper mapper,
        String resource,
        TypeReference<List<T>> type
    ) {
        try (InputStream input = ContentCatalog.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                    "Missing canonical content resource " + resource);
            }
            List<T> values = mapper.readValue(input, type);
            if (values == null) {
                throw invalid(resource, "top-level JSON value must be an array");
            }
            return values;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Could not parse canonical content resource " + resource, exception);
        }
    }

    private static void requireNonEmpty(List<?> values, String resource) {
        if (values == null || values.isEmpty()) {
            throw invalid(resource, "must contain at least one definition");
        }
    }

    /** The coded effect rows carried by a move, validated against the ability registry. */
    private static List<MoveEffectData> codedEffectRows(MoveData move) {
        if (move.effects == null) return List.of();
        return move.effects.stream()
            .filter(Objects::nonNull)
            .filter(effect -> AbilityEffectType.CODED_MOVE_ACTION.name()
                .equalsIgnoreCase(effect.type))
            .toList();
    }

    private static void requireIdentifier(String value, String field) {
        requireText(value, field);
        if (!value.equals(value.trim())) {
            throw new IllegalStateException("Invalid canonical " + field + ": surrounding whitespace");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Invalid canonical content: missing " + field);
        }
    }

    private static IllegalStateException invalid(String resource, String message) {
        return new IllegalStateException("Invalid canonical content in " + resource + ": " + message);
    }

    private static IllegalStateException invalid(
        String resource,
        String message,
        Throwable cause
    ) {
        return new IllegalStateException(
            "Invalid canonical content in " + resource + ": " + message, cause);
    }
}
