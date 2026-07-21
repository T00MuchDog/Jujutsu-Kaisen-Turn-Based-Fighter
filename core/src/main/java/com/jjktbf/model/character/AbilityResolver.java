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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Predicate;

/** Resolves explicitly assigned and prerequisite-granted abilities for a character. */
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

    /**
     * Resolve ability acquisition to a fixed point. An acquired ability may
     * grant a move or technique which then unlocks another sourced ability.
     */
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

    public static Result resolve(
        CharacterData character,
        List<AbilityData> definitions,
        Predicate<String> moveExists,
        List<InnateTechniqueData> techniques
    ) {
        if (character == null || definitions == null || definitions.isEmpty()) {
            return Result.empty(character);
        }

        Predicate<String> validMove = moveExists == null ? ignored -> true : moveExists;

        Set<String> explicitIds = new LinkedHashSet<>(
            character.abilityIds == null ? List.of() : character.abilityIds);
        Set<String> availableMoveIds = new LinkedHashSet<>();
        if (character.moveIds != null) {
            character.moveIds.stream().filter(validMove).forEach(availableMoveIds::add);
        }
        Set<String> grantedMoveIds = new LinkedHashSet<>();
        Map<String, String> techniqueNames = new LinkedHashMap<>();
        addTechnique(techniqueNames, character.innateTechniqueName);

        Map<String, AbilityData> resolved = new LinkedHashMap<>();
        boolean changed;
        do {
            changed = false;
            for (AbilityData definition : definitions) {
                if (definition == null || resolved.containsKey(keyOf(definition))) continue;
                if (!isEligible(definition, character, explicitIds, availableMoveIds,
                                techniqueNames.keySet(), resolved.values(), techniques)) {
                    continue;
                }

                resolved.put(keyOf(definition), definition);
                collectGrants(
                    definition, availableMoveIds, grantedMoveIds, techniqueNames, validMove);
                changed = true;
            }
        } while (changed);

        return new Result(
            new ArrayList<>(resolved.values()),
            new ArrayList<>(grantedMoveIds),
            new LinkedHashSet<>(techniqueNames.values())
        );
    }

    private static boolean isEligible(
        AbilityData definition,
        CharacterData character,
        Set<String> explicitIds,
        Set<String> availableMoveIds,
        Set<String> techniqueNames,
        java.util.Collection<AbilityData> resolved,
        List<InnateTechniqueData> techniques
    ) {
        String source = definition.sourceType == null
            ? "CHARACTER" : definition.sourceType.trim().toUpperCase(Locale.ROOT);

        return switch (source) {
            case "CHARACTER" -> definition.id != null && explicitIds.contains(definition.id);
            // Technique ownership makes a node eligible to be authored in its
            // technique tree; the character must still explicitly activate it.
            case "TECHNIQUE" -> definition.id != null && explicitIds.contains(definition.id)
                && containsIgnoreCase(techniqueNames, definition.sourceValue)
                && treeAllows(definition, character, techniques);
            case "MOVE" -> definition.sourceValue != null
                && availableMoveIds.contains(definition.sourceValue);
            case "STAT_THRESHOLD" -> parseStatRequirement(definition.sourceValue)
                .map(requirement -> requirement.isMetBy(character))
                .orElse(false);
            case "ABILITY" -> resolved.stream().anyMatch(ability ->
                matchesAbilityReference(ability, definition.sourceValue));
            default -> definition.id != null && explicitIds.contains(definition.id);
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

    private static void collectGrants(
        AbilityData ability,
        Set<String> availableMoveIds,
        Set<String> grantedMoveIds,
        Map<String, String> techniqueNames,
        Predicate<String> moveExists
    ) {
        if (ability.isActive() && ability.isQueued()
            && ability.activeMoveId != null && !ability.activeMoveId.isBlank()) {
            if (moveExists.test(ability.activeMoveId)) {
                availableMoveIds.add(ability.activeMoveId);
                grantedMoveIds.add(ability.activeMoveId);
            }
        }
        if (!ability.isPassive() || !ability.isAlwaysActive() || ability.effects == null) return;

        for (AbilityEffectData effect : ability.effects) {
            if (effect == null || effect.type == null) continue;
            try {
                switch (AbilityEffectType.fromName(effect.type)) {
                    case GRANT_MOVE -> {
                        if (effect.moveId != null && !effect.moveId.isBlank()
                            && moveExists.test(effect.moveId)) {
                            availableMoveIds.add(effect.moveId);
                            grantedMoveIds.add(effect.moveId);
                        }
                    }
                    case UNLOCK_TECHNIQUE -> addTechnique(techniqueNames, effect.stringValue);
                    default -> { }
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid effects are rejected by the editor and skipped by runtime resolution.
            }
        }
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
        private final List<String> grantedMoveIds;
        private final Set<String> accessibleTechniqueNames;

        private Result(List<AbilityData> abilities,
                       List<String> grantedMoveIds,
                       Set<String> accessibleTechniqueNames) {
            this.abilities = List.copyOf(abilities);
            this.grantedMoveIds = List.copyOf(grantedMoveIds);
            this.accessibleTechniqueNames = Collections.unmodifiableSet(accessibleTechniqueNames);
        }

        private static Result empty(CharacterData character) {
            Set<String> techniques = new LinkedHashSet<>();
            if (character != null && character.innateTechniqueName != null
                && !character.innateTechniqueName.isBlank()) {
                techniques.add(character.innateTechniqueName);
            }
            return new Result(List.of(), List.of(), techniques);
        }

        public List<AbilityData> abilities() {
            return abilities;
        }

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
                .filter(AbilityData::isAlwaysActive)
                .mapToInt(AbilityData::statBonusPoints)
                .sum();
        }

        public List<String> lockedMoveTags() {
            return abilities.stream()
                .filter(AbilityData::isPassive)
                .filter(AbilityData::isAlwaysActive)
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
