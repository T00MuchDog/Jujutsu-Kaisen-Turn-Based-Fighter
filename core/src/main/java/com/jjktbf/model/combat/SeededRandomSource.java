package com.jjktbf.model.combat;

import java.util.Objects;
import java.util.Random;

/**
 * {@link RandomSource} backed by one owned {@link Random} sequence.
 *
 * <p>Use the seed constructor for reproducible server-owned battles, the
 * {@link Random} constructor when adapting an existing caller, or the no-arg
 * constructor for a non-reproducible local battle.
 */
public final class SeededRandomSource implements RandomSource {

    private final Random random;

    public SeededRandomSource() {
        this(new Random());
    }

    public SeededRandomSource(long seed) {
        this(new Random(seed));
    }

    public SeededRandomSource(Random random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    @Override
    public double nextDouble() {
        return random.nextDouble();
    }

    @Override
    public boolean nextBoolean() {
        return random.nextBoolean();
    }
}
