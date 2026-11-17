# Knot's spoken calls

Short recordings of the parts of a call in Knot ([#154](https://github.com/CruxCoach/CruxCoach/issues/154)). The app
plays them one after another ("Player 2" – "right hand" – "blue"), so the calls work on every phone, also without a
speech engine and without a network. One folder per language; the app picks German when it runs in German and English
otherwise.

| Clip | English | German |
|---|---|---|
| `player_1` … `player_3` | "Player one" … | „Spieler eins“ … |
| `left_hand`, `right_hand`, `left_foot`, `right_foot` | "Left hand" … | „Linke Hand“ … |
| `blue`, `yellow`, `magenta`, `white` | Aurora boards | |
| `green`, `red` (and `blue`) | MoonBoard | |

Ogg Vorbis, mono, 22.05 kHz, about 4 to 8 KB each. Made once with [`scripts/build_knot_voice.py`](../../../../../scripts/build_knot_voice.py),
which says exactly how.

## Where the voices come from

The clips were synthesised offline with the [Piper](https://github.com/OHF-Voice/piper1-gpl) speech synthesiser
(1.8) from two voice models of [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices), which publishes
the models under the MIT licence:

- **English:** `en_US-ljspeech-high`, trained from scratch on the
  [LJ Speech dataset](https://keithito.com/LJ-Speech-Dataset/), which is in the public domain.
- **German:** `de_DE-thorsten-medium`, trained on the [Thorsten-Voice](https://github.com/thorstenMueller/Thorsten-Voice)
  dataset (CC0), fine-tuned from the `rdh` base model (CC0).

Voices fine-tuned from the `lessac` model were left out on purpose: its training data comes with terms of its own.

## Licence

The voice models are MIT-licensed and their training data is public domain (LJ Speech) or CC0 (Thorsten-Voice, `rdh`),
so nothing in their terms restricts shipping recordings made with them. The clips in this folder (`*.ogg`) are part of
CruxCoach and are licensed like the rest of the repository (GPL-3.0, see `LICENSE`). Credit for the voices, as above:
Piper voices `en_US-ljspeech-high` and `de_DE-thorsten-medium` (rhasspy/piper-voices, MIT), from the LJ Speech dataset
(public domain) and the Thorsten-Voice dataset by Thorsten Müller (CC0).
