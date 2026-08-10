package com.jjktbf.server.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjktbf.model.character.Ability;
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
import com.jjktbf.model.character.coded.CodedAbilityRegistry;
import com.jjktbf.model.character.coded.NewShadowStyleAbility;
import com.jjktbf.model.move.Move;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveEffectData;
import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.SkillTreeNodeData;
import com.jjktbf.model.technique.TechniqueSkillTree;

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

/** Server-owned canonical content loaded only from immutable classpath resources. */
public final class ContentCatalog {
    public static final String MOVES_RESOURCE = "/data/moves/all_moves.json";
    public static final String CHARACTERS_RESOURCE = "/data/characters/all_characters.json";
    public static final String ABILITIES_RESOURCE = "/data/abilities/all_abilities.json";
    public static final String TECHNIQUES_RESOURCE = "/data/techniques/all_techniques.json";

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
        return build(moves, characters, abilities, techniques);
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
        List<InnateTechniqueData> techniqueDefinitions
    ) {
        requireNonEmpty(moveDefinitions, MOVES_RESOURCE);
        requireNonEmpty(characterDefinitions, CHARACTERS_RESOURCE);
        if (abilityDefinitions == null) {
            throw invalid(ABILITIES_RESOURCE, "top-level JSON value must be an array");
        }
        if (techniqueDefinitions == null) {
            throw invalid(TECHNIQUES_RESOURCE, "top-level JSON value must be an array");
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
                        + " must reference a physical, reinforced, stunning melee SWORD move");
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
            }
        }

        Set<String> abilityIds = new LinkedHashSet<>();
        for (AbilityData definition : abilityDefinitions) {
            if (definition == null) {
                throw invalid(ABILITIES_RESOURCE, "contains a null ability definition");
            }
            requireIdentifier(definition.id, "ability ID");
            requireText(definition.name, "ability name for " + definition.id);
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
            AbilityResolver.Result resolved = AbilityResolver.resolve(
                definition, abilityDefinitions, movesById::containsKey, techniqueDefinitions);
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
                    if (move.mustBeGranted()
                        && !resolved.availableMoveIds().contains(moveId)) {
                        throw invalid(CHARACTERS_RESOURCE,
                            "character " + definition.id
                                + " learns unavailable grant-only move " + moveId);
                    }
                    InnateTechniqueData technique = moveData == null ? null
                        : TechniqueSkillTree.techniqueByName(
                            techniqueDefinitions, moveData.requiredTechniqueId);
                    SkillTreeNodeData node = technique == null ? null
                        : TechniqueSkillTree.nodeForContent(
                            technique, SkillTreeNodeData.MOVE, moveId);
                    if (node != null && (!TechniqueSkillTree.isActive(node, definition)
                        || !TechniqueSkillTree.isUnlocked(technique, node, definition))) {
                        throw invalid(CHARACTERS_RESOURCE,
                        "character " + definition.id
                                + " does not meet technique-tree prerequisites for move " + moveId);
                    }
                }
                moves.add(move);
            }
            List<Ability> abilities = resolved.toDomainAbilities();
            try {
                Character character = definition.constructTypedCharacter(
                    definition.toCharacterStats(), moves, abilities);
                character.setTitle(definition.title);
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
            } catch (IllegalArgumentException exception) {
                throw invalid(CHARACTERS_RESOURCE,
                    "invalid character " + definition.id + ": " + exception.getMessage(),
                    exception);
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
                if (effect == null || !AbilityEffectType.SUMMON_CHARACTER.name()
                    .equalsIgnoreCase(effect.type)) continue;
                if (effect.characterId == null || effect.characterId.isBlank()) {
                    throw invalid(MOVES_RESOURCE,
                        "move " + definition.id + " has no summon target");
                }
                verifySummonReference(effect.characterId, charactersById,
                    MOVES_RESOURCE, "move " + definition.id);
            }
        }
        for (AbilityData definition : abilities == null ? List.<AbilityData>of() : abilities) {
            if (definition == null || definition.effects == null) continue;
            for (AbilityEffectData effect : definition.effects) {
                if (effect == null) continue;
                boolean summonEffect = AbilityEffectType.SUMMON_CHARACTER.name()
                    .equalsIgnoreCase(effect.type);
                if (summonEffect && (effect.characterId == null
                    || effect.characterId.isBlank())) {
                    throw invalid(ABILITIES_RESOURCE,
                        "ability " + definition.id + " has no summon target");
                }
                if (effect.characterId == null || effect.characterId.isBlank()) continue;
                verifySummonReference(effect.characterId, charactersById,
                    ABILITIES_RESOURCE, "ability " + definition.id);
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
