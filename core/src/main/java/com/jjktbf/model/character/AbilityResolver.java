package com.jjktbf.model.character;

import com.jjktbf.model.technique.InnateTechniqueData;
import com.jjktbf.model.technique.SkillTreeNodeData;
import com.jjktbf.model.technique.TechniqueRepository;
import com.jjktbf.model.technique.TechniqueSkillTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves ability availability, assigned abilities, and their acquisition effects. */
public final class AbilityResolver {

    private static final Pattern STAT_THRESHOLD = Pattern.compile(
        "^\\s*([A-Za-z][A-Za-z0-9_ ]*)\\s*>=\\s*(-?\\d+)\\s*$");

    private AbilityResolver() { }

    public static Result resolve(CharacterData character, AbilityRepository repository) {
        return resolve(character, repository == null ? List.of() : repository.getAll());
    }

    public static Result resolve(
        CharacterData character,
        AbilityRepository repository,
        Predicate<String> moveExists
    ) {
        return resolve(character, repository == null ? List.of() : repository.getAll(), moveExists);
    }

    public static Result resolve(
        CharacterData character,
        AbilityRepository repository,
        Predicate<String> moveExists,
        TechniqueRepository techniqueRepository
    ) {
        return resolve(
            character,
            repository == null ? List.of() : repository.getAll(),
            moveExists,
            techniqueRepository == null ? null : techniqueRepository.getAll());
    }

    public static Result resolve(CharacterData character, List<AbilityData> definitions) {
        return resolve(character, definitions, ignored -> true);
    }

    public static Result resolve(
        CharacterData character,
        List<AbilityData> definitions,
        Predicate<String> moveExists
    ) {
        return resolve(character, definitions, moveExists, null);
    }

    /** Resolve availability and assigned acquisition effects to a fixed point. */
    public static Result resolve(
        CharacterData character,
        List<AbilityData> definitions,
        Predicate<String> moveExists,
        List<InnateTechniqueData> techniques
    ) {
        if (character == null) return Result.empty(null);

        List<AbilityData> availableDefinitions = definitions == null ? List.of() : definitions;
        Predicate<String> validMove = moveExists == null ? ignored -> true : moveExists;
        Set<String> assignedAbilityIds = new LinkedHashSet<>(
            character.abilityIds == null ? List.of() : character.abilityIds);
        Set<String> learnedMoveIds = new LinkedHashSet<>();
        if (character.moveIds != null) {
            character.moveIds.stream().filter(validMove).forEach(learnedMoveIds::add);
        }
        Set<String> availableMoveIds = new LinkedHashSet<>();
        if (character.availableMoveIds != null) {
            character.availableMoveIds.stream().filter(validMove).forEach(availableMoveIds::add);
        }
        Set<String> availableAbilityIds = new LinkedHashSet<>();
        Set<String> grantedMoveIds = new LinkedHashSet<>();
        Map<String, String> techniqueNames = new LinkedHashMap<>();
        addTechnique(techniqueNames, character.innateTechniqueName);

        Map<String, AbilityData> assignedAbilities = new LinkedHashMap<>();
        boolean changed;
        do {
            changed = false;
            for (AbilityData definition : availableDefinitions) {
                if (definition == null || definition.id == null || definition.id.isBlank()) continue;
                if (isSourceAvailable(definition, character, learnedMoveIds,
                    techniqueNames.keySet(), assignedAbilities.values(), techniques)) {
                    changed |= availableAbilityIds.add(definition.id);
                }
            }
            for (AbilityData definition : availableDefinitions) {
                if (definition == null || definition.id == null
                    || !assignedAbilityIds.contains(definition.id)
                    || !availableAbilityIds.contains(definition.id)
                    || assignedAbilities.containsKey(keyOf(definition))) {
                    continue;
                }
                assignedAbilities.put(keyOf(definition), definition);
                changed = true;
                changed |= collectAcquisition(
                    definition,
                    availableMoveIds,
                    availableAbilityIds,
                    grantedMoveIds,
                    techniqueNames,
                    validMove);
            }
        } while (changed);

        return new Result(
            new ArrayList<>(assignedAbilities.values()),
            new ArrayList<>(availableMoveIds),
            new ArrayList<>(availableAbilityIds),
            new ArrayList<>(grantedMoveIds),
            new LinkedHashSet<>(techniqueNames.values())
        );
    }

    private static boolean isSourceAvailable(
        AbilityData definition,
        CharacterData character,
        Set<String> learnedMoveIds,
        Set<String> techniqueNames,
        java.util.Collection<AbilityData> assignedAbilities,
        List<InnateTechniqueData> techniques
    ) {
        String source = definition.sourceType == null
            ? "CHARACTER" : definition.sourceType.trim().toUpperCase(Locale.ROOT);
        return switch (source) {
            case "CHARACTER" -> true;
            case "TECHNIQUE" -> containsIgnoreCase(techniqueNames, definition.sourceValue)
                && treeAllows(definition, character, techniques);
            case "MOVE" -> definition.sourceValue != null
                && learnedMoveIds.contains(definition.sourceValue);
            case "STAT_THRESHOLD" -> parseStatRequirement(definition.sourceValue)
                .map(requirement -> requirement.isMetBy(character))
                .orElse(false);
            case "ABILITY" -> assignedAbilities.stream().anyMatch(ability ->
                matchesAbilityReference(ability, definition.sourceValue));
            default -> false;
        };
    }

    private static boolean treeAllows(
        AbilityData definition,
        CharacterData character,
        List<InnateTechniqueData> techniques
    ) {
        if (techniques == null) return true;
        InnateTechniqueData technique = TechniqueSkillTree.techniqueByName(
            techniques, definition.sourceValue);
        if (technique == null) return false;
        SkillTreeNodeData node = TechniqueSkillTree.nodeForContent(
            technique, SkillTreeNodeData.ABILITY, definition.id);
        return node != null && TechniqueSkillTree.isActive(node, character)
            && TechniqueSkillTree.isUnlocked(technique, node, character);
    }

    private static boolean collectAcquisition(
        AbilityData ability,
        Set<String> availableMoveIds,
        Set<String> availableAbilityIds,
        Set<String> grantedMoveIds,
        Map<String, String> techniqueNames,
        Predicate<String> moveExists
    ) {
        boolean changed = false;
        if (!ability.isPassive() || ability.effects == null) {
            return changed;
        }

        for (AbilityEffectData effect : ability.effects) {
            if (effect == null || effect.type == null) continue;
            try {
                switch (AbilityEffectType.fromName(effect.type)) {
                    case GRANT_MOVE -> {
                        if (validMove(effect.moveId, moveExists)) {
                            changed |= availableMoveIds.add(effect.moveId);
                            grantedMoveIds.add(effect.moveId);
                        }
                    }
                    case UNLOCK_MOVE -> {
                        if (validMove(effect.moveId, moveExists)) {
                            changed |= availableMoveIds.add(effect.moveId);
                        }
                    }
                    case GRANT_ABILITY -> {
                        if (effect.abilityId != null && !effect.abilityId.isBlank()) {
                            changed |= availableAbilityIds.add(effect.abilityId);
                        }
                    }
                    case UNLOCK_TECHNIQUE -> {
                        int before = techniqueNames.size();
                        addTechnique(techniqueNames, effect.stringValue);
                        changed |= techniqueNames.size() != before;
                    }
                    default -> { }
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid effects are rejected by the editor and skipped by resolution.
            }
        }
        return changed;
    }

    private static boolean validMove(String moveId, Predicate<String> moveExists) {
        return moveId != null && !moveId.isBlank() && moveExists.test(moveId);
    }

    private static boolean matchesAbilityReference(AbilityData ability, String reference) {
        if (reference == null || reference.isBlank()) return false;
        return reference.equalsIgnoreCase(ability.id) || reference.equalsIgnoreCase(ability.name);
    }

    private static boolean containsIgnoreCase(Set<String> normalizedNames, String candidate) {
        return candidate != null && normalizedNames.contains(normalize(candidate));
    }

    private static void addTechnique(Map<String, String> names, String techniqueName) {
        if (techniqueName == null || techniqueName.isBlank()) return;
        names.putIfAbsent(normalize(techniqueName), techniqueName.trim());
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String keyOf(AbilityData ability) {
        if (ability.id != null && !ability.id.isBlank()) return "id:" + ability.id;
        return "name:" + String.valueOf(ability.name).toLowerCase(Locale.ROOT);
    }

    public static Optional<StatRequirement> parseStatRequirement(String expression) {
        if (expression == null) return Optional.empty();
        Matcher matcher = STAT_THRESHOLD.matcher(expression);
        if (!matcher.matches()) return Optional.empty();
        try {
            StatKey stat = StatKey.fromString(matcher.group(1));
            int minimum = Integer.parseInt(matcher.group(2));
            return Optional.of(new StatRequirement(stat, minimum));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public record StatRequirement(StatKey stat, int minimum) {
        public boolean isMetBy(CharacterData character) {
            return stat.get(character) >= minimum;
        }

        public String expression() {
            return stat.fieldName + ">=" + minimum;
        }
    }

    public static final class Result {
        private final List<AbilityData> abilities;
        private final List<String> availableMoveIds;
        private final List<String> availableAbilityIds;
        private final List<String> grantedMoveIds;
        private final Set<String> accessibleTechniqueNames;

        private Result(
            List<AbilityData> abilities,
            List<String> availableMoveIds,
            List<String> availableAbilityIds,
            List<String> grantedMoveIds,
            Set<String> accessibleTechniqueNames
        ) {
            this.abilities = List.copyOf(abilities);
            this.availableMoveIds = List.copyOf(availableMoveIds);
            this.availableAbilityIds = List.copyOf(availableAbilityIds);
            this.grantedMoveIds = List.copyOf(grantedMoveIds);
            this.accessibleTechniqueNames = Collections.unmodifiableSet(accessibleTechniqueNames);
        }

        private static Result empty(CharacterData character) {
            Set<String> techniques = new LinkedHashSet<>();
            if (character != null && character.innateTechniqueName != null
                && !character.innateTechniqueName.isBlank()) {
                techniques.add(character.innateTechniqueName);
            }
            List<String> availableMoves = character != null && character.availableMoveIds != null
                ? character.availableMoveIds : List.of();
            return new Result(
                List.of(), availableMoves, List.of(), List.of(), techniques);
        }

        public List<AbilityData> abilities() {
            return abilities;
        }

        public List<String> availableMoveIds() {
            return availableMoveIds;
        }

        public List<String> availableAbilityIds() {
            return availableAbilityIds;
        }

        /**
         * Move IDs granted via {@link AbilityEffectType#GRANT_MOVE}, which bypass
         * all learning requirements. These are also present in
         * {@link #availableMoveIds()}; this accessor exposes the bypass subset.
         */
        public List<String> grantedMoveIds() {
            return grantedMoveIds;
        }

        public Set<String> accessibleTechniqueNames() {
            return accessibleTechniqueNames;
        }

        public boolean hasTechnique(String techniqueName) {
            if (techniqueName == null) return false;
            return accessibleTechniqueNames.stream()
                .anyMatch(name -> name.equalsIgnoreCase(techniqueName));
        }

        public boolean containsAbility(String id) {
            return id != null && abilities.stream().anyMatch(ability -> id.equals(ability.id));
        }

        public int statBonusPoints() {
            return abilities.stream()
                .filter(AbilityData::isPassive)
                .mapToInt(AbilityData::statBonusPoints)
                .sum();
        }

        /** Highest assigned passive allocation floor for each stat. */
        public Map<StatKey, Integer> statAllocationMinimums() {
            Map<StatKey, Integer> minimums = new java.util.EnumMap<>(StatKey.class);
            abilities.stream()
                .filter(AbilityData::isPassive)
                .filter(ability -> ability.effects != null)
                .flatMap(ability -> ability.effects.stream())
                .filter(java.util.Objects::nonNull)
                .filter(effect -> AbilityEffectType.STAT_ALLOCATION_MINIMUM.name()
                    .equalsIgnoreCase(effect.type))
                .forEach(effect -> {
                    if (effect.intValue == null) return;
                    try {
                        StatKey stat = StatKey.fromString(effect.stat);
                        minimums.merge(stat, effect.intValue, Math::max);
                    } catch (IllegalArgumentException ignored) {
                        // Ability validation reports malformed stat references.
                    }
                });
            return Collections.unmodifiableMap(minimums);
        }

        public int statAllocationMinimum(StatKey stat) {
            return statAllocationMinimums().getOrDefault(stat, CharacterStats.MIN_STAT);
        }

        /** Lowest assigned passive allocation ceiling for each stat. */
        public Map<StatKey, Integer> statAllocationMaximums() {
            Map<StatKey, Integer> maximums = new java.util.EnumMap<>(StatKey.class);
            abilities.stream()
                .filter(AbilityData::isPassive)
                .filter(ability -> ability.effects != null)
                .flatMap(ability -> ability.effects.stream())
                .filter(java.util.Objects::nonNull)
                .filter(effect -> AbilityEffectType.STAT_ALLOCATION_MAXIMUM.name()
                    .equalsIgnoreCase(effect.type))
                .forEach(effect -> {
                    if (effect.intValue == null) return;
                    try {
                        StatKey stat = StatKey.fromString(effect.stat);
                        maximums.merge(stat, effect.intValue, Math::min);
                    } catch (IllegalArgumentException ignored) {
                        // Ability validation reports malformed stat references.
                    }
                });
            return Collections.unmodifiableMap(maximums);
        }

        public int statAllocationMaximum(StatKey stat) {
            return statAllocationMaximums().getOrDefault(stat, CharacterStats.MAX_STAT);
        }

        public List<String> lockedMoveTags() {
            return abilities.stream()
                .filter(AbilityData::isPassive)
                .filter(ability -> ability.effects != null)
                .flatMap(ability -> ability.effects.stream())
                .filter(java.util.Objects::nonNull)
                .filter(effect -> AbilityEffectType.LOCK_MOVE_TAG.name().equalsIgnoreCase(effect.type))
                .map(effect -> effect.moveTag)
                .filter(tag -> tag != null && !tag.isBlank())
                .distinct()
                .toList();
        }

        public List<Ability> toDomainAbilities() {
            return abilities.stream().map(Ability::new).toList();
        }
    }
}
