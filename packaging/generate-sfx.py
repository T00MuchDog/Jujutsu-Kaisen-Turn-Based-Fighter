#!/usr/bin/env python3
"""Generate the project's original retro UI and battle sound effects."""

from __future__ import annotations

import math
import random
import struct
import wave
from pathlib import Path


SAMPLE_RATE = 44_100
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "graphics/src/main/resources/assets/audio/sfx"


def oscillator(
    duration: float,
    start_hz: float,
    end_hz: float | None = None,
    *,
    shape: str = "square",
    duty: float = 0.5,
    volume: float = 0.5,
    attack: float = 0.002,
    release: float = 0.025,
    decay: float = 0.4,
    vibrato_hz: float = 0.0,
    vibrato_depth: float = 0.0,
) -> list[float]:
    count = max(1, round(duration * SAMPLE_RATE))
    target_hz = start_hz if end_hz is None else end_hz
    phase = 0.0
    result: list[float] = []
    for index in range(count):
        time = index / SAMPLE_RATE
        progress = index / max(1, count - 1)
        frequency = start_hz * ((target_hz / start_hz) ** progress)
        if vibrato_hz:
            frequency *= 1.0 + math.sin(2.0 * math.pi * vibrato_hz * time) * vibrato_depth
        phase = (phase + frequency / SAMPLE_RATE) % 1.0
        if shape == "square":
            # Keep narrow pulse waves centered around zero instead of adding a
            # large DC component that wastes headroom when effects overlap.
            sample = 1.0 if phase < duty else -duty / max(1e-6, 1.0 - duty)
        elif shape == "triangle":
            sample = 1.0 - 4.0 * abs(phase - 0.5)
        elif shape == "saw":
            sample = phase * 2.0 - 1.0
        else:
            sample = math.sin(phase * math.tau)
        envelope = min(1.0, time / max(attack, 1e-6))
        envelope *= min(1.0, (duration - time) / max(release, 1e-6))
        envelope *= (1.0 - progress) ** decay
        result.append(sample * volume * max(0.0, envelope))
    return result


def noise(
    duration: float,
    seed: int,
    *,
    volume: float = 0.5,
    color: str = "white",
    attack: float = 0.001,
    release: float = 0.03,
    decay: float = 1.0,
    gate_hz: float = 0.0,
) -> list[float]:
    rng = random.Random(seed)
    count = max(1, round(duration * SAMPLE_RATE))
    low = 0.0
    previous_low = 0.0
    result: list[float] = []
    for index in range(count):
        time = index / SAMPLE_RATE
        progress = index / max(1, count - 1)
        white = rng.uniform(-1.0, 1.0)
        low += 0.12 * (white - low)
        if color == "low":
            sample = low
        elif color == "high":
            sample = white - low
        elif color == "band":
            sample = low - previous_low
            previous_low = low
            sample *= 4.0
        else:
            sample = white
        envelope = min(1.0, time / max(attack, 1e-6))
        envelope *= min(1.0, (duration - time) / max(release, 1e-6))
        envelope *= (1.0 - progress) ** decay
        if gate_hz and math.sin(math.tau * gate_hz * time) < -0.25:
            envelope *= 0.18
        result.append(sample * volume * max(0.0, envelope))
    return result


def mix(duration: float, *parts: tuple[list[float], float, float]) -> list[float]:
    result = [0.0] * max(1, round(duration * SAMPLE_RATE))
    for samples, offset, gain in parts:
        start = max(0, round(offset * SAMPLE_RATE))
        for index, sample in enumerate(samples):
            target = start + index
            if target >= len(result):
                break
            result[target] += sample * gain
    return result


def notes(
    pitches: list[float],
    note_duration: float,
    *,
    shape: str = "square",
    volume: float = 0.5,
    gap: float = 0.0,
    duty: float = 0.5,
) -> list[float]:
    result: list[float] = []
    for pitch in pitches:
        result.extend(oscillator(
            note_duration,
            pitch,
            shape=shape,
            duty=duty,
            volume=volume,
            release=min(0.02, note_duration * 0.4),
            decay=0.15,
        ))
        result.extend([0.0] * round(gap * SAMPLE_RATE))
    return result


def bitcrush(samples: list[float], levels: int = 48, hold: int = 2) -> list[float]:
    result: list[float] = []
    held = 0.0
    for index, sample in enumerate(samples):
        if index % hold == 0:
            held = round(sample * levels) / levels
        result.append(held)
    return result


def write_wav(relative_path: str, samples: list[float], peak: float = 0.9) -> None:
    destination = OUTPUT / relative_path
    destination.parent.mkdir(parents=True, exist_ok=True)
    mean = sum(samples) / max(1, len(samples))
    samples = [sample - mean for sample in samples]
    fade_samples = min(len(samples) // 2, round(0.003 * SAMPLE_RATE))
    for index in range(fade_samples):
        gain = index / max(1, fade_samples)
        samples[index] *= gain
        samples[-index - 1] *= gain
    maximum = max((abs(sample) for sample in samples), default=1.0)
    scale = peak / maximum if maximum > peak else 1.0
    pcm = bytearray()
    for sample in samples:
        clipped = max(-1.0, min(1.0, sample * scale))
        pcm.extend(struct.pack("<h", round(clipped * 32767.0)))
    with wave.open(str(destination), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(pcm)


def sand_step(seed: int) -> list[float]:
    return mix(
        0.075,
        (noise(0.07, seed, volume=0.75, color="high", release=0.018, decay=1.8), 0.0, 1.0),
        (oscillator(0.045, 170, 95, shape="triangle", volume=0.22, decay=1.5), 0.002, 1.0),
    )


def generate_ui() -> dict[str, list[float]]:
    return {
        "ui/navigate.wav": bitcrush(mix(
            0.065,
            (oscillator(0.055, 2050, 2650, duty=0.25, volume=0.52, decay=0.7), 0.0, 1.0),
            (oscillator(0.045, 1025, 1325, duty=0.5, volume=0.20, decay=0.9), 0.006, 1.0),
        ), 40, 2),
        "ui/confirm.wav": bitcrush(mix(
            0.14,
            (notes([1046.5, 1568.0], 0.055, volume=0.48, gap=0.004, duty=0.25), 0.0, 1.0),
            (oscillator(0.12, 523.25, 784.0, shape="triangle", volume=0.18, decay=0.8), 0.008, 1.0),
        ), 56, 2),
        "ui/back.wav": bitcrush(mix(
            0.14,
            (notes([1396.9, 932.3], 0.052, shape="triangle", volume=0.48, gap=0.006), 0.0, 1.0),
            (oscillator(0.12, 698.5, 349.2, duty=0.375, volume=0.17, decay=1.0), 0.005, 1.0),
        ), 48, 2),
        "ui/toggle.wav": bitcrush(mix(
            0.075,
            (oscillator(0.032, 720, 1040, duty=0.2, volume=0.55, decay=0.4), 0.0, 1.0),
            (oscillator(0.032, 1040, 720, duty=0.2, volume=0.40, decay=0.5), 0.034, 1.0),
        ), 36, 2),
        "ui/delete.wav": bitcrush(mix(
            0.17,
            (oscillator(0.15, 760, 165, shape="saw", volume=0.42, decay=1.2), 0.0, 1.0),
            (noise(0.105, 101, volume=0.35, color="high", decay=2.4), 0.015, 1.0),
        ), 40, 3),
        "ui/denied.wav": bitcrush(mix(
            0.22,
            (notes([311.13, 277.18], 0.09, volume=0.43, gap=0.012, duty=0.5), 0.0, 1.0),
            (oscillator(0.20, 155.6, 138.6, shape="triangle", volume=0.24, decay=0.7), 0.0, 1.0),
        ), 32, 3),
        "ui/pickup.wav": bitcrush(mix(
            0.11,
            (oscillator(0.095, 430, 2100, duty=0.25, volume=0.48, decay=0.55), 0.0, 1.0),
            (noise(0.045, 102, volume=0.18, color="high", decay=2.0), 0.0, 1.0),
        ), 48, 2),
        "ui/drop.wav": bitcrush(mix(
            0.12,
            (noise(0.09, 103, volume=0.54, color="high", decay=2.2), 0.0, 1.0),
            (oscillator(0.09, 240, 92, shape="triangle", volume=0.38, decay=1.7), 0.008, 1.0),
        ), 40, 2),
        "ui/plan_place.wav": bitcrush(mix(
            0.21,
            (sand_step(110), 0.0, 0.85),
            (sand_step(111), 0.052, 1.0),
            (sand_step(112), 0.108, 0.72),
        ), 38, 2),
        "ui/plan_remove.wav": bitcrush(mix(
            0.15,
            (noise(0.10, 113, volume=0.48, color="high", decay=1.8), 0.0, 1.0),
            (oscillator(0.13, 980, 180, duty=0.25, volume=0.40, decay=1.0), 0.0, 1.0),
        ), 42, 2),
        "ui/plan_lock.wav": bitcrush(mix(
            0.30,
            (notes([523.25, 783.99, 1046.5], 0.072, volume=0.46, gap=0.006, duty=0.25), 0.0, 1.0),
            (oscillator(0.27, 261.6, 523.25, shape="triangle", volume=0.18, decay=0.55), 0.0, 1.0),
        ), 56, 2),
    }


def generate_battle() -> dict[str, list[float]]:
    return {
        "battle/attack_unleash.wav": bitcrush(mix(
            0.30,
            (oscillator(0.26, 190, 1650, shape="saw", volume=0.50, decay=0.4), 0.0, 1.0),
            (noise(0.18, 201, volume=0.26, color="high", decay=1.4, gate_hz=32), 0.035, 1.0),
        ), 52, 2),
        "battle/defense_unleash.wav": bitcrush(mix(
            0.30,
            (oscillator(0.25, 420, 980, shape="triangle", volume=0.43, vibrato_hz=27, vibrato_depth=0.035), 0.0, 1.0),
            (oscillator(0.19, 1260, 1850, duty=0.125, volume=0.26, decay=0.9), 0.045, 1.0),
        ), 60, 2),
        "battle/utility_unleash.wav": bitcrush(mix(
            0.34,
            (notes([392.0, 523.25, 659.25, 783.99], 0.065, shape="triangle", volume=0.45, gap=0.006), 0.0, 1.0),
            (oscillator(0.30, 196, 392, duty=0.25, volume=0.17, decay=0.5), 0.0, 1.0),
        ), 58, 2),
        "battle/hit.wav": bitcrush(mix(
            0.29,
            (oscillator(0.24, 1180, 105, shape="square", duty=0.3, volume=0.50, decay=0.8, vibrato_hz=46, vibrato_depth=0.11), 0.0, 1.0),
            (noise(0.25, 202, volume=0.58, color="high", decay=1.15, gate_hz=37), 0.0, 1.0),
            (oscillator(0.17, 155, 64, shape="triangle", volume=0.42, decay=1.5), 0.025, 1.0),
        ), 34, 3),
        "battle/block.wav": bitcrush(mix(
            0.25,
            (noise(0.12, 203, volume=0.48, color="high", decay=2.5), 0.0, 1.0),
            (oscillator(0.22, 1850, 780, shape="triangle", volume=0.46, vibrato_hz=38, vibrato_depth=0.06, decay=0.9), 0.0, 1.0),
            (oscillator(0.10, 170, 90, shape="triangle", volume=0.34, decay=1.8), 0.0, 1.0),
        ), 56, 2),
        "battle/miss.wav": bitcrush(mix(
            0.28,
            (noise(0.24, 204, volume=0.44, color="high", attack=0.025, decay=0.8, gate_hz=18), 0.0, 1.0),
            (oscillator(0.22, 880, 240, shape="sine", volume=0.18, decay=0.6), 0.025, 1.0),
        ), 64, 2),
        "battle/dodge.wav": bitcrush(mix(
            0.22,
            (noise(0.18, 205, volume=0.52, color="high", attack=0.015, decay=1.2, gate_hz=25), 0.0, 1.0),
            (oscillator(0.16, 480, 1450, shape="sine", volume=0.20, decay=0.7), 0.015, 1.0),
        ), 58, 2),
        "battle/parry.wav": bitcrush(mix(
            0.32,
            (oscillator(0.27, 2850, 2050, shape="triangle", volume=0.48, vibrato_hz=31, vibrato_depth=0.025, decay=1.0), 0.0, 1.0),
            (noise(0.055, 206, volume=0.48, color="high", decay=2.7), 0.0, 1.0),
            (notes([1174.7, 1760.0], 0.055, volume=0.25, gap=0.008, duty=0.125), 0.025, 1.0),
        ), 62, 2),
        "battle/black_flash.wav": bitcrush(mix(
            0.48,
            (noise(0.40, 207, volume=0.65, color="low", decay=1.2, gate_hz=22), 0.0, 1.0),
            (oscillator(0.37, 95, 38, shape="saw", volume=0.55, decay=0.9), 0.0, 1.0),
            (oscillator(0.27, 3200, 240, duty=0.125, volume=0.38, decay=0.7, vibrato_hz=57, vibrato_depth=0.10), 0.025, 1.0),
        ), 32, 3),
        "battle/ignored.wav": bitcrush(mix(
            0.22,
            (oscillator(0.18, 520, 220, shape="triangle", volume=0.40, vibrato_hz=24, vibrato_depth=0.05), 0.0, 1.0),
            (noise(0.09, 208, volume=0.25, color="high", decay=2.0), 0.0, 1.0),
        ), 44, 2),
        "battle/heal.wav": bitcrush(mix(
            0.48,
            (notes([523.25, 659.25, 783.99, 1046.5, 1318.5], 0.07, shape="triangle", volume=0.44, gap=0.009), 0.0, 1.0),
            (oscillator(0.42, 261.6, 1046.5, shape="sine", volume=0.16, decay=0.5), 0.0, 1.0),
        ), 64, 2),
        "battle/ce_drain.wav": bitcrush(mix(
            0.24,
            (oscillator(0.21, 1450, 260, duty=0.2, volume=0.40, decay=0.8), 0.0, 1.0),
            (noise(0.16, 209, volume=0.20, color="high", decay=1.3), 0.025, 1.0),
        ), 48, 3),
        "battle/ce_restore.wav": bitcrush(mix(
            0.28,
            (oscillator(0.24, 260, 1650, duty=0.2, volume=0.42, decay=0.6), 0.0, 1.0),
            (notes([659.25, 987.77], 0.07, shape="triangle", volume=0.22, gap=0.012), 0.08, 1.0),
        ), 52, 2),
        "battle/stun.wav": bitcrush(mix(
            0.34,
            (oscillator(0.29, 440, 250, shape="square", duty=0.5, volume=0.46, vibrato_hz=19, vibrato_depth=0.16, decay=0.6), 0.0, 1.0),
            (notes([622.25, 466.16, 622.25], 0.07, volume=0.25, gap=0.018, duty=0.25), 0.0, 1.0),
        ), 36, 3),
        "battle/status_apply.wav": bitcrush(mix(
            0.30,
            (notes([392.0, 493.88, 587.33], 0.066, shape="triangle", volume=0.38, gap=0.012), 0.0, 1.0),
            (oscillator(0.25, 196, 587.33, duty=0.25, volume=0.15, decay=0.7), 0.0, 1.0),
        ), 52, 2),
        "battle/status_expire.wav": bitcrush(mix(
            0.25,
            (notes([587.33, 493.88, 392.0], 0.058, shape="triangle", volume=0.34, gap=0.012), 0.0, 1.0),
            (noise(0.18, 210, volume=0.14, color="high", decay=1.0), 0.03, 1.0),
        ), 48, 2),
        "battle/ability.wav": bitcrush(mix(
            0.44,
            (notes([392.0, 587.33, 783.99, 1174.7], 0.074, volume=0.42, gap=0.008, duty=0.25), 0.0, 1.0),
            (oscillator(0.38, 196, 783.99, shape="triangle", volume=0.17, decay=0.45), 0.0, 1.0),
        ), 58, 2),
        "battle/ratio.wav": bitcrush(mix(
            0.38,
            (notes([880.0, 880.0, 1318.5, 1760.0], 0.052, volume=0.43, gap=0.022, duty=0.125), 0.0, 1.0),
            (noise(0.16, 211, volume=0.24, color="high", decay=1.8), 0.17, 1.0),
        ), 44, 2),
        "battle/round_start.wav": bitcrush(mix(
            0.48,
            (notes([392.0, 523.25, 659.25, 783.99], 0.077, volume=0.42, gap=0.012, duty=0.25), 0.0, 1.0),
            (oscillator(0.42, 196, 392, shape="triangle", volume=0.15, decay=0.5), 0.0, 1.0),
        ), 58, 2),
        "battle/round_end.wav": bitcrush(mix(
            0.42,
            (notes([783.99, 659.25, 523.25], 0.085, shape="triangle", volume=0.40, gap=0.018), 0.0, 1.0),
            (oscillator(0.35, 392, 196, duty=0.25, volume=0.16, decay=0.8), 0.0, 1.0),
        ), 52, 2),
        "battle/victory.wav": bitcrush(mix(
            0.92,
            (notes([523.25, 659.25, 783.99, 1046.5, 783.99, 1046.5], 0.105, volume=0.43, gap=0.018, duty=0.25), 0.0, 1.0),
            (notes([261.63, 329.63, 392.0], 0.22, shape="triangle", volume=0.18, gap=0.025), 0.0, 1.0),
        ), 62, 2),
        "battle/defeat.wav": bitcrush(mix(
            0.86,
            (notes([523.25, 466.16, 392.0, 311.13, 261.63], 0.125, shape="triangle", volume=0.42, gap=0.025), 0.0, 1.0),
            (oscillator(0.75, 196, 82.4, duty=0.5, volume=0.15, decay=1.1), 0.0, 1.0),
        ), 44, 3),
        "battle/draw.wav": bitcrush(mix(
            0.72,
            (notes([392.0, 523.25, 466.16, 392.0], 0.12, shape="triangle", volume=0.40, gap=0.022), 0.0, 1.0),
            (oscillator(0.62, 196, 196, duty=0.25, volume=0.13, decay=0.9), 0.0, 1.0),
        ), 52, 2),
    }


def main() -> None:
    effects = generate_ui() | generate_battle()
    for relative_path, samples in effects.items():
        write_wav(relative_path, samples)
    print(f"Generated {len(effects)} effects in {OUTPUT}")


if __name__ == "__main__":
    main()
