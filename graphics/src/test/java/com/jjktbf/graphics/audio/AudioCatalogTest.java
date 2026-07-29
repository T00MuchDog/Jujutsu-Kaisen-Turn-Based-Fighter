package com.jjktbf.graphics.audio;

import com.badlogic.gdx.backends.lwjgl3.audio.Wav;
import com.badlogic.gdx.files.FileHandle;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioCatalogTest {
    @Test
    void everyRegisteredAssetHasAUniquePathAndValidGain() {
        Set<String> paths = new HashSet<>();
        for (MusicTrack track : MusicTrack.values()) {
            assertTrue(paths.add(track.assetPath()), track.assetPath());
            assertTrue(track.assetPath().startsWith("assets/audio/music/"));
            assertTrue(track.gain() >= 0f && track.gain() <= 1f);
        }
        for (SoundCue cue : SoundCue.values()) {
            assertTrue(paths.add(cue.assetPath()), cue.assetPath());
            assertTrue(cue.assetPath().startsWith("assets/audio/sfx/"));
            assertNotEquals(AudioChannel.MUSIC, cue.channel());
            assertTrue(cue.gain() >= 0f && cue.gain() <= 1f);
        }
    }

    @Test
    void everyRegisteredSoundEffectIsPackaged() {
        ClassLoader loader = AudioCatalogTest.class.getClassLoader();
        for (SoundCue cue : SoundCue.values()) {
            assertNotNull(loader.getResource(cue.assetPath()), cue.assetPath());
        }
    }

    @Test
    void everyPackagedWavCanBeDecodedByLibGdx() throws Exception {
        ClassLoader loader = AudioCatalogTest.class.getClassLoader();
        for (SoundCue cue : SoundCue.values()) {
            if (!cue.assetPath().endsWith(".wav")) continue;

            URL resource = loader.getResource(cue.assetPath());
            assertNotNull(resource, cue.assetPath());
            FileHandle file = new FileHandle(Path.of(resource.toURI()).toFile());
            try (Wav.WavInputStream wav = new Wav.WavInputStream(file)) {
                assertTrue(wav.dataRemaining > 0, cue.assetPath());
            }
        }
    }

    @Test
    void oggValidationRejectsRenamedWebmAndAcceptsVorbis() {
        byte[] webm = {
            0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 'A', '_', 'O', 'P', 'U', 'S'
        };
        byte[] vorbis = new byte[64];
        vorbis[0] = 'O';
        vorbis[1] = 'g';
        vorbis[2] = 'g';
        vorbis[3] = 'S';
        vorbis[5] = 0x02;
        vorbis[26] = 1;
        vorbis[27] = 30;
        vorbis[28] = 0x01;
        byte[] marker = {'v', 'o', 'r', 'b', 'i', 's'};
        System.arraycopy(marker, 0, vorbis, 29, marker.length);

        assertFalse(GameAudio.isOggVorbisHeader(webm));
        assertTrue(GameAudio.isOggVorbisHeader(vorbis));
    }
}
