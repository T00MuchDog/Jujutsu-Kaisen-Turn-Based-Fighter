# Audio Assets

See the repository-level `AUDIO.md` for the registered filenames, trigger
conditions, formats, and steps for adding new cues. The runtime silently skips
a missing catalog asset, while tests require all registered effects to ship.

The checked-in sound effects are primarily original procedural assets. The block
effect is a CC0 Kenney asset; see `SFX_PROVENANCE.md` for full details. Regenerate
the procedural effects from the repository root with:

```bash
python3 packaging/generate-sfx.py
```

The generator intentionally preserves `sfx/battle/block.wav`.

An `.ogg` filename must contain a real Ogg Vorbis stream. Renaming WebM/Opus to
`.ogg` does not convert it and LibGDX cannot decode it as registered music.
