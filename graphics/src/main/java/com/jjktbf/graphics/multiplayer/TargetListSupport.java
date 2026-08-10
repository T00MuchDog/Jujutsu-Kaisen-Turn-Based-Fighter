package com.jjktbf.graphics.multiplayer;

import com.jjktbf.model.combat.ActionSegment;
import com.jjktbf.model.combat.CombatantId;
import com.jjktbf.model.move.AoeType;
import com.jjktbf.multiplayer.protocol.MoveState;
import com.jjktbf.multiplayer.protocol.PlanPlacement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Bridges graphics code across the scalar-to-list targeting protocol rollout. */
public final class TargetListSupport {
    private TargetListSupport() { }

    public static List<CombatantId> segmentTargets(ActionSegment segment) {
        if (segment == null) return List.of();
        Object targets = invokeOptional(segment, "getTargets");
        if (targets instanceof List<?> list) {
            List<CombatantId> result = new ArrayList<>();
            for (Object value : list) {
                if (value instanceof CombatantId id && !result.contains(id)) result.add(id);
            }
            return List.copyOf(result);
        }

        Object target = invokeOptional(segment, "getTarget");
        return target instanceof CombatantId id ? List.of(id) : List.of();
    }

    public static void setSegmentTargets(ActionSegment segment, List<CombatantId> targets) {
        if (segment == null) return;
        List<CombatantId> normalized = distinctIds(targets);
        try {
            Method setter = segment.getClass().getMethod("setTargets", List.class);
            setter.invoke(segment, normalized);
            return;
        } catch (NoSuchMethodException ignored) {
            // The scalar fallback disappears once the core list API lands.
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not set action targets", exception);
        }

        try {
            Method setter = segment.getClass().getMethod("setTarget", CombatantId.class);
            setter.invoke(segment, normalized.isEmpty() ? null : normalized.get(0));
        } catch (NoSuchMethodException ignored) {
            // A list-only ActionSegment needs no fallback.
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not set action target", exception);
        }
    }

    public static PlanPlacement placement(
        String moveId,
        int startTick,
        String actorId,
        List<String> targetIds
    ) {
        List<String> normalized = distinctStrings(targetIds);
        RecordComponent[] components = PlanPlacement.class.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            parameterTypes[i] = component.getType();
            arguments[i] = switch (component.getName()) {
                case "moveId" -> moveId;
                case "startTick" -> startTick;
                case "actorId" -> actorId;
                case "targetId" -> normalized.isEmpty() ? null : normalized.get(0);
                case "targetIds" -> normalized;
                default -> defaultValue(component.getType());
            };
        }
        try {
            Constructor<PlanPlacement> constructor =
                PlanPlacement.class.getDeclaredConstructor(parameterTypes);
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported PlanPlacement protocol shape", exception);
        }
    }

    public static List<String> targetIds(Object protocolValue) {
        if (protocolValue == null) return List.of();
        Method targetIds = method(protocolValue, "targetIds");
        if (targetIds != null) {
            try {
                Object value = targetIds.invoke(protocolValue);
                return value instanceof List<?> list ? distinctStrings(list) : List.of();
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not read protocol targetIds", exception);
            }
        }

        Object targetId = invokeOptional(protocolValue, "targetId");
        return targetId instanceof String id && !id.isBlank() ? List.of(id) : List.of();
    }

    public static AoeType moveStateAoeType(MoveState move) {
        Object value = invokeOptional(move, "aoeType");
        if (value instanceof AoeType type) return type;
        return value == null ? null : AoeType.fromName(value.toString());
    }

    public static int moveStateAoeTargetCount(MoveState move) {
        Object value = invokeOptional(move, "aoeTargetCount");
        return value instanceof Number number ? Math.max(1, number.intValue()) : 1;
    }

    private static Method method(Object target, String name) {
        try {
            return target.getClass().getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Object invokeOptional(Object target, String name) {
        if (target == null) return null;
        Method method = method(target, name);
        if (method == null) return null;
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not invoke " + name, exception);
        }
    }

    private static List<CombatantId> distinctIds(List<CombatantId> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<CombatantId> result = new LinkedHashSet<>();
        for (CombatantId value : values) {
            if (value != null) result.add(value);
        }
        return List.copyOf(result);
    }

    private static List<String> distinctStrings(List<?> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (value == null) continue;
            String id = value.toString();
            if (!id.isBlank()) result.add(id);
        }
        return List.copyOf(result);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        throw new IllegalArgumentException("Unknown primitive " + type);
    }
}
