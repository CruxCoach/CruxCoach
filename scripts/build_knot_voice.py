#!/usr/bin/env python3
"""Build Knot's spoken calls: one short Ogg Vorbis clip per word group, in English and German.

Knot (#154) calls "Player 2 – right hand – blue" while everyone is on the wall, on phones that may have no
speech engine at all. So the app ships the parts of a call as recordings and plays them one after another.
This script makes them once, offline, with the Piper speech synthesiser and two voices whose training data
is free to reuse:

- English: Piper voice `en_US-ljspeech-high`, trained from scratch on the LJ Speech dataset (public domain).
- German: Piper voice `de_DE-thorsten-medium`, trained on the Thorsten-Voice dataset (CC0), fine-tuned from
  the `rdh` base model (CC0).

The voice models are published under the MIT licence in https://huggingface.co/rhasspy/piper-voices. See
`androidApp/src/main/assets/knot_voice/README.md` for the licences of the clips.

Needs Python 3.10+, `pip install piper-tts` (1.8) and ffmpeg with libvorbis. The models are downloaded by hand
into MODELS (the `.onnx` and `.onnx.json` files from the repository above):

    scripts/build_knot_voice.py --models MODELS [--out androidApp/src/main/assets/knot_voice] [--only NAME,...]

The synthesis is not exactly repeatable, so `--only` makes just the named clips and leaves the others as they are.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

VOICES = {
    "en": "en_US-ljspeech-high",
    "de": "de_DE-thorsten-medium",
}

# File name -> what is said. The app plays player (unless solo), then limb, then colour.
CLIPS = {
    "en": {
        "player_1": "Player one.",
        "player_2": "Player two.",
        "player_3": "Player three.",
        "player_4": "Player four.",
        "player_5": "Player five.",
        "player_6": "Player six.",
        "player_7": "Player seven.",
        "player_8": "Player eight.",
        "to_the_wall": "To the wall.",
        "left_hand": "Left hand.",
        "right_hand": "Right hand.",
        "left_foot": "Left foot.",
        "right_foot": "Right foot.",
        "blue": "Blue.",
        "yellow": "Yellow.",
        "magenta": "Magenta.",
        "white": "White.",
        "green": "Green.",
        "red": "Red.",
    },
    "de": {
        "player_1": "Spieler eins.",
        "player_2": "Spieler zwei.",
        "player_3": "Spieler drei.",
        "player_4": "Spieler vier.",
        "player_5": "Spieler fünf.",
        "player_6": "Spieler sechs.",
        "player_7": "Spieler sieben.",
        "player_8": "Spieler acht.",
        "to_the_wall": "An die Wand.",
        "left_hand": "Linke Hand.",
        "right_hand": "Rechte Hand.",
        "left_foot": "Linker Fuß.",
        "right_foot": "Rechter Fuß.",
        "blue": "Blau.",
        "yellow": "Gelb.",
        "magenta": "Magenta.",
        "white": "Weiß.",
        "green": "Grün.",
        "red": "Rot.",
    },
}

LENGTH_SCALE = 0.9
COLOURS = {"blue", "yellow", "magenta", "white", "green", "red"}

# Silence trimmed at both ends, loudness evened out, mono 22.05 kHz Vorbis: about 4 to 8 KB a clip.
FILTER = (
    "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02,"
    "areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.05,areverse,"
    "loudnorm=I=-16:TP=-1.5:LRA=7"
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--models", type=Path, required=True, help="folder with the Piper .onnx and .onnx.json files")
    parser.add_argument("--out", type=Path, default=Path("androidApp/src/main/assets/knot_voice"))
    parser.add_argument("--only", help="comma-separated clip names to make; all when left out")
    args = parser.parse_args()
    only = set(args.only.split(",")) if args.only else None
    unknown = (only or set()) - set(CLIPS["en"])
    if unknown:
        parser.error(f"unknown clips: {', '.join(sorted(unknown))}")

    from piper import PiperVoice, SynthesisConfig  # imported here so --help works without Piper

    # A little brisker than the voices' own pace: a call has to fit the hardest level's 3 seconds. The colour, the
    # word that matters most and the shortest, keeps the voice's own pace.
    brisk = SynthesisConfig(length_scale=LENGTH_SCALE)
    natural = SynthesisConfig()

    for language, voice_name in VOICES.items():
        voice = PiperVoice.load(args.models / f"{voice_name}.onnx")
        target = args.out / language
        target.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory() as tmp:
            for name, text in CLIPS[language].items():
                if only is not None and name not in only:
                    continue
                raw = Path(tmp) / f"{name}.wav"
                with wave.open(str(raw), "wb") as wav:
                    voice.synthesize_wav(text, wav, syn_config=natural if name in COLOURS else brisk)
                subprocess.run(
                    [
                        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(raw),
                        "-af", FILTER, "-ac", "1", "-ar", "22050",
                        "-c:a", "libvorbis", "-q:a", "2", "-map_metadata", "-1",
                        str(target / f"{name}.ogg"),
                    ],
                    check=True,
                )
                print(f"{language}/{name}.ogg  {text}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
