package com.jjktbf.model.character;

/**
 * Root of the entity hierarchy.
 * Anything that can participate in combat extends Entity.
 *
 * Deliberately minimal — only the fields that are truly universal belong here.
 */
public abstract class Entity {

    private final String id;
    private final String name;

    protected Entity(String id, String name) {
        if (id == null || id.isBlank())     throw new IllegalArgumentException("Entity id cannot be blank");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Entity name cannot be blank");
        this.id   = id;
        this.name = name;
    }

    public String getId()   { return id; }
    public String getName() { return name; }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{id='" + id + "', name='" + name + "'}";
    }
}
