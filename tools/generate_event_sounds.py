"""Generates the original event tones in android/app/src/main/res/raw (issue #238).

Pure-Python synthesis to WAV, then ffmpeg encodes to OGG Vorbis:
    python tools/generate_event_sounds.py
"""
import math
import struct
import subprocess
import tempfile
import wave
from pathlib import Path

RATE = 44100
OUT = Path(__file__).resolve().parent.parent / "android/app/src/main/res/raw"


def tone(freq, dur, vol=0.5, decay=6.0, harmonics=((1, 1.0), (2, 0.25), (3, 0.08))):
    """A bell-like tone: a few harmonics under an attack ramp and exponential decay."""
    n = int(RATE * dur)
    out = []
    for i in range(n):
        t = i / RATE
        env = min(1.0, t / 0.005) * math.exp(-decay * t)
        s = sum(a * math.sin(2 * math.pi * freq * h * t) for h, a in harmonics)
        out.append(vol * env * s / 1.33)
    return out


def silence(dur):
    return [0.0] * int(RATE * dur)


def write_ogg(name, samples):
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / f"{name}.wav"
        with wave.open(str(wav), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))
        OUT.mkdir(parents=True, exist_ok=True)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-c:a", "libvorbis", "-q:a", "4", str(OUT / f"{name}.ogg")], check=True)


# Cabin-chime single "ding" (chat).
write_ogg("sound_chat", tone(1175, 0.7, decay=5.0))
# Two rising chimes (contact me) -- more insistent.
write_ogg("sound_contact_me", tone(880, 0.35, decay=7.0) + tone(1320, 0.7, decay=5.0))
# SELCAL-style: two quick two-tone pairs.
pair = tone(1046, 0.22, decay=4.0) + tone(1397, 0.22, decay=4.0)
write_ogg("sound_selcal", pair + silence(0.12) + pair)
