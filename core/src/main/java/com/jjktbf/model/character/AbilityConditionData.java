package com.jjktbf.model.character;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive predicate used to activate a passive ability.
 *
 * <p>{@code ALL} and {@code ANY} nodes contain child predicates. Every other
 * type is a leaf. Missing activation data is treated as {@code ALWAYS}, keeping
 * older ability JSON valid.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AbilityConditionData {

    public String type;
    public String actor;
    public Double percentage;
    public Integer amount;
    public String moveId;
    public String moveTag;
    public String stat;
    public String statusType;
    public Integer tick;
    public Integer round;
    public String phase;
    public List<AbilityConditionData> children;

    public static AbilityConditionData always() {
        return AbilityConditionType.ALWAYS.createDefault();
    }

    public static AbilityConditionData all(List<AbilityConditionData> children) {
        AbilityConditionData condition = AbilityConditionType.ALL.createDefault();
        condition.children = copyChildren(children);
        return condition;
    }

    public static AbilityConditionData any(List<AbilityConditionData> children) {
        AbilityConditionData condition = AbilityConditionType.ANY.createDefault();
        condition.children = copyChildren(children);
        return condition;
    }

    public AbilityConditionData copy() {
        AbilityConditionData copy = new AbilityConditionData();
        copy.copyFrom(this);
        return copy;
    }

    public void copyFrom(AbilityConditionData source) {
        type = source.type;
        actor = source.actor;
        percentage = source.percentage;
        amount = source.amount;
        moveId = source.moveId;
        moveTag = source.moveTag;
        stat = source.stat;
        statusType = source.statusType;
        tick = source.tick;
        round = source.round;
        phase = source.phase;
        children = copyChildren(source.children);
    }

    public boolean containsAlways() {
        if (AbilityConditionType.ALWAYS.name().equalsIgnoreCase(type)) return true;
        return children != null && children.stream()
            .filter(java.util.Objects::nonNull)
            .anyMatch(AbilityConditionData::containsAlways);
    }

    private static List<AbilityConditionData> copyChildren(List<AbilityConditionData> source) {
        if (source == null) return null;
        return source.stream()
            .filter(java.util.Objects::nonNull)
            .map(AbilityConditionData::copy)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
