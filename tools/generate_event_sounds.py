"""Generates the synthesized event tones in android/app/src/main/res/raw (issue #238):
sound_chat (radio squelch burst), sound_radio_to_you (short 600 Hz double blip, for a radio call
addressed to this aircraft) and sound_selcal (a real SELCAL-style call).
sound_contact_me is NOT generated here -- it is a licensed cabin ding-dong recording, converted
once with: ffmpeg -i dingdong.mp3 -ac 1 -c:a libvorbis -q:a 4 sound_contact_me.ogg

Pure-Python synthesis to WAV, then ffmpeg encodes to OGG Vorbis:
    python tools/generate_event_sounds.py
The synthesis is seeded and deterministic: the .ogg bytes differ per run (the encoder writes a
random stream id), but the decoded audio is identical to the committed files.
"""
import math
import random
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


def write_ogg(name, samples, audio_filter=None):
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / f"{name}.wav"
        with wave.open(str(wav), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))
        OUT.mkdir(parents=True, exist_ok=True)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), *(["-af", audio_filter] if audio_filter else []), "-c:a", "libvorbis", "-q:a", "4", str(OUT / f"{name}.ogg")], check=True)


# Radio squelch (normal chat): a short burst of plain white noise with a flat body and tiny edge
# fades (no click). A real squelch burst is essentially just this -- noise that stops abruptly.
def squelch(dur=0.15, peak=0.14, seed=238):
    rng = random.Random(seed)
    n = int(RATE * dur)
    noise = [rng.gauss(0, 1) for _ in range(n)]
    scale = peak / max(abs(x) for x in noise)
    attack, release = int(RATE * 0.004), int(RATE * 0.015)
    return [
        x * scale * min(1.0, i / attack, (n - 1 - i) / release)
        for i, x in enumerate(noise)
    ]


write_ogg("sound_chat", squelch())
# Radio message addressed to this aircraft: a very short, punchy double blip -- two ~50 ms pulses of
# a 600 Hz sine, each opening with a brief bright ~6 kHz "tik", a few ms apart. Modelled on the
# character of a classic radio client's direct-message alert (measured, not copied), and clearly
# distinct from the noise-burst chat sound and the longer contact-me ding-dong.
def blip_pulse(click_hz, dur=0.05, body_hz=600, vol=0.4):
    n = int(RATE * dur)
    out = []
    for i in range(n):
        t = i / RATE
        env = min(1.0, t / 0.002, (dur - t) / 0.008)
        click = 0.6 * math.exp(-t / 0.006) * math.sin(2 * math.pi * click_hz * t)
        out.append(vol * env * (math.sin(2 * math.pi * body_hz * t) + click) / 1.4)
    return out


write_ogg("sound_radio_to_you", blip_pulse(6050) + silence(0.005) + blip_pulse(5700))
# SELCAL: two successive bursts of ~1 s, each two simultaneous pure tones from the standard 16
# SELCAL frequencies (A=312.6 ... S=1479.1 Hz), ~0.2 s apart -- here the code "AM-CS" --
# then run through hf_radio() for a shortwave-radio sound.
def burst(f1, f2, dur=1.0, vol=0.35):
    n = int(RATE * dur)
    out = []
    for i in range(n):
        t = i / RATE
        env = min(1.0, t / 0.01, (dur - t) / 0.01)
        out.append(vol * env * (math.sin(2 * math.pi * f1 * t) + math.sin(2 * math.pi * f2 * t)))
    return out


def hf_radio(samples, seed=238):
    """Shortwave-radio treatment: static lead-in/tail, hiss, crackle pops, slow QSB fading and
    mild saturation. The band-limiting (voice-radio passband) is applied by ffmpeg afterwards."""
    rng = random.Random(seed)
    lead, tail = int(RATE * 0.15), int(RATE * 0.25)
    padded = [0.0] * lead + list(samples) + [0.0] * tail
    n = len(padded)
    out = []
    pop = 0.0
    for i, x in enumerate(padded):
        t = i / RATE
        # Slow signal fading (QSB): 1.1 Hz and 0.37 Hz components, never fully silent.
        fade = 0.7 + 0.2 * math.sin(2 * math.pi * 1.1 * t + 0.8) + 0.1 * math.sin(2 * math.pi * 0.37 * t)
        # Louder static when no signal is present (lead-in, tail), steady hiss underneath.
        in_signal = lead <= i < n - tail
        hiss = rng.gauss(0, 0.05 if in_signal else 0.16)
        # Occasional crackle pops that die away quickly.
        if rng.random() < 0.0006:
            pop = rng.choice((-1, 1)) * rng.uniform(0.3, 0.7)
        pop *= 0.985
        y = math.tanh(2.2 * x * fade) / math.tanh(2.2) + hiss + pop
        out.append(0.6 * y)
    return out


# Voice-radio passband (300-3000 Hz) keeps the 312.6 Hz and 1479.1 Hz tones inside it.
write_ogg(
    "sound_selcal",
    hf_radio(burst(312.6, 977.2) + silence(0.2) + burst(384.6, 1479.1)),
    audio_filter="highpass=f=280,lowpass=f=3200",
)
