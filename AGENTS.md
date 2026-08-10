# Repository Instructions

## Game-Mechanic Composition

IMPORTANT: Hardcode reusable effects, not moves, abilities, passives, techniques,
or characters.

- Treat effect types and their runtime implementations as immutable primitives.
- Build moves by composing ordered effect rows with a trigger, target, optional
  condition tree, activation chance, and CTM progression.
- Build active and passive abilities from the same reusable effect vocabulary
  and condition system.
- Do not add runtime branches keyed by a move, ability, technique, or character
  ID/name when the behavior can be represented by existing effect primitives.
- When a genuinely new mechanic is required, add one generic, reusable effect
  primitive and expose it to data and editor tooling before using it in content.
- Keep authored content in data files. Compatibility readers may translate old
  persisted fields, but new canonical content must use effect compositions.
