"""Generates Ribbon's original menu sounds as small 16-bit mono WAV files.

    python3 tools/make_sounds.py <style>             writes app/src/main/res/raw/snd_*.wav
    python3 tools/make_sounds.py <style> --preview DIR  writes DIR/<style>-preview.wav (a short demo)

Styles: glass, ambient, digital, deep

Everything is synthesized here (sine/FM partials, soft envelopes, a small reverb), so the
sounds are original to this project.
"""
import array
import math
import os
import random
import sys
import wave

RATE = 44100
TAU = 2 * math.pi
RAW = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


# ---- building blocks ---------------------------------------------------------------------

def tone(freq, dur, decay, partials=((1.0, 1.0),), attack=0.004, glide=0.0, fm=0.0, fm_ratio=2.0, detune=0.0):
    """Sum of partials with an exponential decay. fm adds a 2-operator FM 'digital' edge;
    detune adds a second slightly-detuned copy for a chorus-like width."""
    n = int(dur * RATE)
    out = [0.0] * n
    voices = [(1.0, 1.0)] + ([(1.0 + detune, 0.7)] if detune else [])
    for vmul, vamp in voices:
        for ratio, amp in partials:
            ph = mph = 0.0
            for i in range(n):
                t = i / RATE
                f = freq * ratio * vmul * (1.0 + glide * t / dur)
                env = min(1.0, t / attack) * math.exp(-t / (decay / ratio ** 0.5))
                mph += TAU * f * fm_ratio / RATE
                ph += TAU * f / RATE
                out[i] += vamp * amp * env * math.sin(ph + fm * env * math.sin(mph))
    return out


def noise_whoosh(dur, center=0.5, width=0.25, bright=0.35, seed=7):
    """Filtered noise swell (one-pole low-pass), for airy movement."""
    rnd = random.Random(seed)
    n = int(dur * RATE)
    out, lp = [0.0] * n, 0.0
    for i in range(n):
        t = i / dur / RATE
        env = math.exp(-((t - center) / width) ** 2)
        lp += bright * (rnd.uniform(-1, 1) - lp)
        out[i] = lp * env
    return out


def mix(*parts):
    """parts: (offset_seconds, samples, gain)"""
    end = max(int(o * RATE) + len(s) for o, s, _ in parts)
    out = [0.0] * end
    for o, s, g in parts:
        st = int(o * RATE)
        for i, v in enumerate(s):
            out[st + i] += v * g
    return out


def reverb(x, wet=0.3, size=1.0, tail=0.8):
    """Small Schroeder reverb: 4 parallel combs into 2 all-passes. Adds a tail of `tail` seconds."""
    x = x + [0.0] * int(tail * RATE)
    combs = [int(d * size) for d in (1557, 1617, 1491, 1422)]
    fb = 0.80
    wet_sig = [0.0] * len(x)
    for d in combs:
        buf = [0.0] * d
        k = 0
        for i, v in enumerate(x):
            y = buf[k]
            buf[k] = v + y * fb
            k = (k + 1) % d
            wet_sig[i] += y * 0.25
    for d, g in ((225, 0.5), (556, 0.5)):
        buf = [0.0] * d
        k = 0
        for i, v in enumerate(wet_sig):
            b = buf[k]
            y = -v + b
            buf[k] = v + b * g
            k = (k + 1) % d
            wet_sig[i] = y
    return [d * (1 - wet) + w * wet for d, w in zip(x, wet_sig)]


def finish(samples, peak_db):
    fade = int(0.01 * RATE)
    s = list(samples)
    for i in range(min(fade, len(s))):
        s[-1 - i] *= i / fade
    peak = max(abs(v) for v in s) or 1.0
    g = 10 ** (peak_db / 20) / peak
    return [v * g for v in s]


def write(path, samples):
    data = array.array("h", (int(max(-1.0, min(1.0, v)) * 32767) for v in samples))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data.tobytes())


# ---- styles ------------------------------------------------------------------------------

BELL = ((1.0, 1.0), (2.76, 0.32), (5.40, 0.12))


def style_glass():
    return {
        "move": finish(tone(1975.5, 0.07, 0.018, ((1.0, 1.0), (2.0, 0.25), (3.01, 0.08)), glide=-0.04), -16),
        "category": finish(tone(1318.5, 0.11, 0.03, ((1.0, 1.0), (2.0, 0.3), (2.76, 0.1)), glide=-0.03), -15),
        "select": finish(mix((0.0, tone(1318.5, 0.45, 0.12, BELL), 0.8), (0.07, tone(1975.5, 0.5, 0.16, BELL), 1.0)), -9),
        "back": finish(mix((0.0, tone(987.8, 0.3, 0.07, BELL), 0.9), (0.06, tone(659.3, 0.38, 0.1, BELL), 0.8)), -13),
        "edge": finish(tone(392.0, 0.09, 0.02, ((1.0, 1.0), (2.0, 0.2))), -20),
    }


def style_ambient():
    """Soft, low sine plucks with a wide chorus and a spacious echo tail."""
    soft = ((1.0, 1.0), (2.0, 0.12))
    return {
        "move": finish(reverb(tone(880.0, 0.09, 0.03, soft, attack=0.006, detune=0.004), wet=0.35, tail=0.35), -15),
        "category": finish(reverb(mix((0.0, tone(587.3, 0.16, 0.06, soft, attack=0.01, detune=0.005), 1.0),
                                      (0.0, noise_whoosh(0.22, 0.35, 0.22, 0.08), 0.5)), wet=0.4, tail=0.5), -14),
        "select": finish(reverb(mix((0.0, tone(523.3, 0.6, 0.2, soft, attack=0.01, detune=0.005), 0.9),
                                    (0.08, tone(784.0, 0.7, 0.25, soft, attack=0.01, detune=0.005), 1.0),
                                    (0.16, tone(1046.5, 0.6, 0.2, soft, attack=0.01), 0.5)), wet=0.45, tail=1.0), -8),
        "back": finish(reverb(mix((0.0, tone(659.3, 0.35, 0.1, soft, detune=0.004), 0.9),
                                  (0.07, tone(440.0, 0.45, 0.14, soft, detune=0.004), 0.8)), wet=0.4, tail=0.7), -12),
        "edge": finish(reverb(tone(220.0, 0.12, 0.04, soft), wet=0.25, tail=0.25), -18),
    }


def style_digital():
    """Synthetic FM blips: short, a little metallic, with a tight digital room."""
    return {
        "move": finish(reverb(tone(1174.7, 0.06, 0.02, fm=1.6, fm_ratio=3.0), wet=0.25, size=0.6, tail=0.25), -16),
        "category": finish(reverb(tone(783.99, 0.1, 0.035, fm=2.2, fm_ratio=2.0, glide=0.05), wet=0.3, size=0.7, tail=0.35), -15),
        "select": finish(reverb(mix((0.0, tone(587.3, 0.35, 0.1, fm=2.0, fm_ratio=2.0), 0.9),
                                    (0.06, tone(880.0, 0.35, 0.1, fm=2.0, fm_ratio=2.0), 0.9),
                                    (0.12, tone(1174.7, 0.5, 0.16, fm=1.4, fm_ratio=2.0), 1.0)), wet=0.35, size=0.8, tail=0.7), -9),
        "back": finish(reverb(mix((0.0, tone(880.0, 0.25, 0.07, fm=1.8, fm_ratio=2.0), 0.9),
                                  (0.06, tone(587.3, 0.3, 0.09, fm=1.8, fm_ratio=2.0), 0.8)), wet=0.3, size=0.7, tail=0.5), -13),
        "edge": finish(tone(196.0, 0.08, 0.025, fm=3.0, fm_ratio=1.5), -19),
    }


def style_deep():
    """Warm low tones and soft air, more felt than heard."""
    warm = ((1.0, 1.0), (2.0, 0.35), (3.0, 0.1))
    return {
        "move": finish(reverb(mix((0.0, tone(392.0, 0.1, 0.035, warm, attack=0.003), 1.0),
                                  (0.0, noise_whoosh(0.08, 0.2, 0.15, 0.25, seed=3), 0.25)), wet=0.3, tail=0.3), -14),
        "category": finish(reverb(mix((0.0, tone(293.7, 0.16, 0.06, warm, attack=0.006, glide=-0.03), 1.0),
                                      (0.0, noise_whoosh(0.28, 0.4, 0.25, 0.12), 0.6)), wet=0.4, size=1.2, tail=0.6), -13),
        "select": finish(reverb(mix((0.0, tone(261.6, 0.7, 0.25, warm, attack=0.008), 0.9),
                                    (0.0, tone(392.0, 0.7, 0.25, warm, attack=0.008), 0.7),
                                    (0.09, tone(523.3, 0.8, 0.3, warm, attack=0.008), 0.8)), wet=0.45, size=1.3, tail=1.2), -8),
        "back": finish(reverb(mix((0.0, tone(392.0, 0.35, 0.1, warm), 0.9),
                                  (0.07, tone(261.6, 0.45, 0.14, warm), 0.8)), wet=0.4, size=1.2, tail=0.8), -12),
        "edge": finish(tone(130.8, 0.12, 0.04, warm), -16),
    }


STYLES = {"glass": style_glass, "ambient": style_ambient, "digital": style_digital, "deep": style_deep}


def preview(sounds):
    """move x3, category, move, select, (pause), back, edge."""
    seq = [(0.0, "move"), (0.28, "move"), (0.56, "move"), (1.0, "category"), (1.45, "move"),
           (1.95, "select"), (3.4, "back"), (4.3, "edge")]
    return mix(*[(t, sounds[n], 1.0) for t, n in seq])


if __name__ == "__main__":
    style = sys.argv[1] if len(sys.argv) > 1 else "glass"
    sounds = STYLES[style]()
    if "--preview" in sys.argv:
        out = sys.argv[sys.argv.index("--preview") + 1]
        os.makedirs(out, exist_ok=True)
        path = os.path.join(out, f"{style}-preview.wav")
        write(path, preview(sounds))
        print(path)
    else:
        os.makedirs(RAW, exist_ok=True)
        for name, s in sounds.items():
            write(os.path.join(RAW, f"snd_{name}.wav"), s)
            print(f"snd_{name}.wav  {len(s) / RATE * 1000:.0f} ms")
