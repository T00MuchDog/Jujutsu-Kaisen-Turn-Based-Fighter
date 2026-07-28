# Audio Assets

See the repository-level `AUDIO.md` for the registered filenames, trigger
conditions, formats, and steps for adding new cues. The runtime silently skips
a missing catalog asset, while tests require all registered effects to ship.

The checked-in sound effects are original procedural assets. See
`SFX_PROVENANCE.md`; regenerate them from the repository root with:

```bash
python3 packaging/generate-sfx.py
```

An `.ogg` filename must contain a real Ogg Vorbis stream. Renaming WebM/Opus to
`.ogg` does not convert it and LibGDX cannot decode it as registered music.
