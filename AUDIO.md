# Audio System

The desktop client has one application-lifetime audio service with three mixer
channels:

| Channel | Content | Asset loading |
|---|---|---|
| `MUSIC` | Menu and battle music | Streamed and looped with LibGDX `Music` |
| `UI_SFX` | Navigation, confirmation, back, and planner feedback | Preloaded with LibGDX `Sound` |
| `BATTLE_SFX` | Move, impact, defense, and result effects | Preloaded with LibGDX `Sound` |

`JJKGame` owns `GameAudio`, routes music with screen transitions, pauses and
resumes it with the application, and disposes every native audio resource at
shutdown. Menu music is not restarted while moving among non-battle screens.

Audio files are optional. A catalog entry whose file is absent is a silent
no-op, so conditions can be wired before final audio is available. Restart the
game after adding a file because the catalog is loaded once at startup.

## Asset Layout

Place immutable audio under the graphics resource root:

```text
graphics/src/main/resources/assets/audio/
|-- music/
|   |-- menu.ogg
|   `-- battle_aizo.ogg
`-- sfx/
    |-- ui/
    |   |-- navigate.wav
    |   |-- confirm.wav
    |   |-- back.wav
    |   |-- plan_place.wav
    |   |-- plan_remove.wav
    |   `-- plan_lock.wav
    `-- battle/
        |-- attack_unleash.wav
        |-- defense_unleash.wav
        |-- utility_unleash.wav
        |-- hit.wav
        |-- block.wav
        |-- miss.wav
        |-- dodge.wav
        |-- parry.wav
        |-- black_flash.wav
        |-- victory.wav
        |-- defeat.wav
        `-- draw.wav
```

The existing Maven resource configuration packages this directory into the
desktop JAR. Music must be an actual Ogg Vorbis stream, not WebM/Opus renamed to
`.ogg`; a valid file begins with the `OggS` container header and contains a
Vorbis identification packet. WAV or short OGG files are suitable for effects.
If a supplied file uses another supported extension, update its catalog path to
match the real filename.

## Drop-In Slots

The paths above are already registered and triggered. Supplying a file at one
of those exact paths is enough to enable it:

| File | Existing condition |
|---|---|
| `music/menu.ogg` | Main menu, selection, multiplayer menus, and editors |
| `music/battle_aizo.ogg` | Local and multiplayer battles |
| `sfx/ui/navigate.wav` | Main-menu, character, and editor keyboard navigation |
| `sfx/ui/confirm.wav` | Menu actions, selections, shared multiplayer actions, and next round |
| `sfx/ui/back.wav` | Back, cancel, shared editor dialog dismissal, and battle exit |
| `sfx/ui/plan_*.wav` | Successful placement/removal and an accepted local lock or online send |
| `sfx/battle/*_unleash.wav` | A fired attack, defensive move, or utility move |
| `sfx/battle/hit.wav` | A `DAMAGE_DEALT` event |
| `sfx/battle/block.wav` | A full or partial block event |
| `sfx/battle/miss.wav` | A missed move |
| `sfx/battle/dodge.wav` | A local dodge event |
| `sfx/battle/parry.wav` | A local parry event |
| `sfx/battle/black_flash.wav` | A Black Flash event |
| `sfx/battle/victory.wav`, `defeat.wav`, `draw.wav` | The result shown to the local player |

Battle event sounds run at the visual playback boundary in `BattleScreen`.
This keeps local and multiplayer timing aligned and prevents repeated network
snapshots from replaying effects. Multiple events resolved in the same online
tick intentionally layer their cues, matching the same-tick visual update; the
battle log still reveals their messages in order. Online dodge/parry cues will
also need the multiplayer protocol to expose those event types; the local routes
are ready.

Online confirmation cues acknowledge that the local command was valid and sent.
Authoritative rejection remains a separate server response and UI state change.

## Adding A New Effect

1. Put the file below `graphics/src/main/resources/assets/audio/sfx/ui/` or
   `graphics/src/main/resources/assets/audio/sfx/battle/`.
2. Add one constant to `SoundCue` with its channel, internal asset path, and
   per-cue gain.
3. Trigger it at the semantic condition, on the LibGDX render thread:

```java
game.audio().play(SoundCue.UI_OPEN_SETTINGS);
```

For a standard combat event, add the mapping once in `BattleAudioRouter` so the
same rule serves local and multiplayer playback. For a move-specific unleash,
add its stable move ID to `MOVE_UNLEASH_CUES`; other moves retain the generic
attack, defense, or utility fallback.

Example catalog entry:

```java
UI_OPEN_SETTINGS(
    AudioChannel.UI_SFX,
    "assets/audio/sfx/ui/open_settings.wav",
    0.9f
),
```

Do not call LibGDX audio directly from the deterministic `core` module. Audio is
presentation-only. A worker-thread condition must use `Gdx.app.postRunnable`
before calling `GameAudio`; the local battle integration already does this.

## Adding Music

The existing menu and battle tracks require only their files at the registered
paths. For another music context:

1. Add a `MusicTrack` constant and resource path.
2. Route the relevant screen through `JJKGame.showScreen` with that track.

`GameAudio.playMusic` is idempotent for the active track and loops it until a
different track is requested.

## Mixer Settings

The effective gain is:

```text
master volume * channel volume * catalog cue gain
```

Settings are persisted through the LibGDX preferences named `jjktbf-audio`.
The main-menu gear opens compact music/effects controls. The current API is:

```java
game.audio().setMasterVolume(0.8f);
game.audio().setChannelVolume(AudioChannel.MUSIC, 0.6f);
game.audio().setChannelVolume(AudioChannel.UI_SFX, 0.9f);
game.audio().setChannelVolume(AudioChannel.BATTLE_SFX, 1.0f);
game.audio().setMuted(true);
```

Values are normalized to `0.0` through `1.0`. The single effects control updates
the UI and battle-effect channels together; code can still tune them separately.

## Verification

Run:

```bash
mvn -pl graphics -am test
```

`AudioCatalogTest` catches duplicate paths, invalid gains, and effects assigned
to the music channel. `BattleAudioRouterTest` checks event-to-cue behavior.
