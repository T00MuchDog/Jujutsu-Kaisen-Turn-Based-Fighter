# Audio Assets

See the repository-level `AUDIO.md` for the registered filenames, trigger
conditions, formats, and steps for adding new cues. Missing files are allowed;
the game silently skips any catalog entry without a corresponding asset.

An `.ogg` filename must contain a real Ogg Vorbis stream. Renaming WebM/Opus to
`.ogg` does not convert it and LibGDX cannot decode it as registered music.
