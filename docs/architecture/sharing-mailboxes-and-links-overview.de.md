# Teilen über Briefkästen und Einladungslinks – Überblick (0.2.6-Vorschau)

Die vollständige Beschreibung steht in [sharing-mailboxes-and-links.md](sharing-mailboxes-and-links.md). Sie ist die
Grundlage der externen Prüfung. Gebaut ist das auf `feat/permission-foundations`. Es läuft nur in der Debug-Vorschau,
in keinem Release.

## Was gilt

- **Verschlüsselung:** Olm zwischen je zwei Freunden. Briefkästen und Netz sehen keine Inhalte und keine npub.
- **Zustellung:**
  - direkt über WLAN oder Bluetooth, wenn beide Apps offen sind;
  - sonst über bis zu drei Briefkästen (CruxCoach und je ein öffentliches Relay aus zwei Gruppen);
  - abgelegt gilt ab 2 von 3;
  - nach dem Abholen löscht der Empfänger, spätestens nach 14 Tagen ist alles weg.
- **Adressen und Schlüssel:** Sie wechseln täglich und je Richtung. Welche Briefkästen eine Freundschaft nutzt, leiten
  beide aus ihrem gemeinsamen Geheimnis ab. Keine Liste steht öffentlich.
- **Takt:** Öffentliche Relays bekommen 6 Events sofort, dann 6 je Minute; der eigene Briefkasten mehr. Damit sperrt
  kein öffentliches Relay die App.
- **Einladung:**
  - **QR-Code** beim Treffen: gilt 1 Stunde, einmal nutzbar; beide Telefone zeigen dieselbe Kennung zum Vergleichen.
  - **Link** `https://cruxcoach.org/f/#…`: der Code steht nur im Fragment, das kein Webserver sieht; gilt 7 Tage,
    einmal nutzbar, widerrufbar.
  - Der Aussteller bestätigt jede Anfrage.
- **Nur direkt** (Stufe „Maximal“): Nichts geht in einen Briefkasten. Einladen geht dann nur per QR-Code.
- **Bis zur Owner-Entscheidung F6** nutzt die Vorschau nur den CruxCoach-Briefkasten. Der ist noch nicht in Betrieb;
  den Aufbau macht der Owner (Vorschläge PV1–PV7).

## Was wer sieht (kurz)

| Stelle | sieht |
|---|---|
| CruxCoach-Briefkasten | dass geteilt wird, Tagesadressen, Zeitpunkte, Größenklassen; **keine IP**, solange der Proxy so bleibt |
| Öffentliche Relays | dasselbe plus IP-Adresse |
| Cloudflare | zusätzlich IP, Zeit, Größe |
| Freunde | nur, was mit ihnen geteilt wird |

Noch offen (ab 0.2.7):
- getrennte Verbindungen und Tarn-Adressen;
- eigene Briefkästen;
- Megolm-Verteiler für viele Freunde;
- Tor (ab 0.2.8 zu prüfen).
