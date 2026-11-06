# Was Gewichts- und Zählfelder im Training bedeuten

Eine Regel pro Fall, auf jedem Bildschirm gleich (Issue #58). Im Code steht sie in
`LoadRules` (`shared/src/commonMain/kotlin/com/cruxcoach/athlete/logic/LoadRules.kt`),
Beschriftungen und Wertetexte in `WeightText.kt` in der App.

## Gewicht

| Fall | Was du einträgst | Beschriftung (DE / EN) | Angezeigt als |
|---|---|---|---|
| Zwei Kurzhanteln (Bankdrücken, Schulterdrücken, Rumänisches Kreuzheben, Handgelenkbeugen …) | das Gewicht **einer** Hantel, die Zahl, die draufsteht | Gewicht pro Hantel / Weight per dumbbell | 2 × 16 kg |
| Zwei Kettlebells (eigene Übungen) | das Gewicht einer Kettlebell | Gewicht pro Kettlebell / Weight per kettlebell | 2 × 16 kg |
| Eine Kurzhantel (einarmig, Goblet-Kniebeuge) | die Hantel | Gewicht der Hantel / Dumbbell weight | 16 kg |
| Eine Kettlebell | die Kettlebell | Gewicht der Kettlebell / Kettlebell weight | 16 kg |
| Langhantel | das Gesamtgewicht **inklusive Stange** | Gesamt inkl. Stange / Total incl. bar | 60 kg |
| Einarmiges Blockheben | alles, was du hebst: Block, Pin oder Karabiner und Scheiben | Gesamt inkl. Block / Total incl. block | 32 kg |
| Blockheben beidarmig | beide Hände zusammen, an einem Block oder zweien, Blöcke inklusive | Gesamt, beide Hände / Total, both hands | 50 kg, Hinweis „Mit zwei Blöcken: je 25 kg“ |
| Kabelzug und Maschine | das Gewicht am Gewichtsstapel in kg oder lb, nicht die Nummer der Platte | Gewicht am Kabelzug / Weight on the stack | 40 kg |
| Kraftmesser (Zug an fixierter Leiste) | der Messwert | Wert am Kraftmesser / Gauge reading | 30 kg |
| Körpergewichtsübungen (Klimmzüge, Hängen, Dips, Ausfallschritte) | nur, was du **dazunimmst**, alles zusammen: Gürtel, Weste, beide Hanteln | Zusatzgewicht / Added weight | +12 kg |
| Entlastung (Umlenkrolle, Band, Füße) | eine negative Zahl: die abgenommenen Kilo (beim Band geschätzt) | Zusatzgewicht / Added weight | 15 kg entlastet / 15 kg assisted |
| Nur Körpergewicht | 0 | | Körpergewicht / Bodyweight |

Gespeichert wird in kg. Ist lb eingestellt, zeigt und liest die App lb und rechnet um.

### Summen

Volumen, % Körpergewicht, das geschätzte 1RM, Bestwerte, die Kraftkurve und die
Finger- und Schulterbelastung zählen das wirklich bewegte Gewicht: **beide Hanteln**
eines Paars, bei Körpergewichtsübungen Körpergewicht plus Zusatzgewicht. Die Planung
rechnet zurück und schlägt wieder das Gewicht einer Hantel vor. Geschätzte Maxima
und Bestwerte stehen so da, wie du einträgst („geschätztes 1RM 2 × 23 kg“); bei
Körpergewichtsübungen samt Körpergewicht („100 kg gesamt“).

## Zählen

- **Einseitige Übungen** (Blockheben einarmig, einarmiges Rudern, Step-up) loggen jede
  Seite als eigenen Satz. Wiederholungen, Zeit und Gewicht gelten für diese Seite. Im
  Workout-Editor heißt das Feld dann „Sätze pro Seite“.
- **Abwechselnde Übungen** innerhalb eines Satzes (Ausfallschritt gehend, Dead Bug,
  Bird Dog, Scheibenwischer im Hang): jede Seite zählt als eine Wiederholung, wie es
  in den Schritten der Übung steht.
- **Getippte Workouts:** „2x16 kg“ bei einer Kurzhantelübung sind 16 kg pro Hantel,
  bei einer Körpergewichtsübung +32 kg Zusatzgewicht, beim beidarmigen Blockheben
  32 kg gesamt. Eine neue Übung mit Kurzhanteln und „2x“ wird eine Übung mit zwei
  Hanteln.

## Gespeicherte Sätze werden nie umgeschrieben

Die Regeln ändern nur, wie eine Zahl gelesen wird, nie die gespeicherte Zahl. Weil
die App Kurzhantelübungen schon bisher pro Hantel geplant und die Texteingabe
„2x16 kg“ als 16 gespeichert hat, werden ältere Kurzhantelsätze genauso gelesen.
Ihr Volumen, ihr Anteil am Körpergewicht und ihr geschätztes Maximum zählen jetzt
beide Hanteln, wie neue Sätze, damit die Kurven nicht springen.
