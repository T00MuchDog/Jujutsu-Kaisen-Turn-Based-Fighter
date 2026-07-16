package com.jjktbf.model.combat;

/**
 * Project-owned source of authoritative battle randomness.
 *
 * <p>Battle owners can supply a seeded implementation to reproduce an exact
 * sequence without coupling combat rules to a particular random generator.
 */
public interface RandomSource {

    int nextInt(int bound);

    double nextDouble();

    boolean nextBoolean();
}
