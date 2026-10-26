# Testanleitung: Persönliche Daten teilen (Vorschau 0.2.6)

Stand: 26. Oktober 2026, Branch `feat/permission-foundations`. Gebaut ist Olm zwischen je zwei Freunden mit Zustellung
über Briefkästen und direkt beim Treffen; beschrieben in
[Teilen über Briefkästen und Links](../architecture/sharing-mailboxes-and-links-overview.de.md).

Feature-Identität laut `scripts/feature_identity.py`:
- APKTrack-Track `feat-permission-foundations-5aa1fa27`;
- Paket `com.cruxcoach.android.dev.f_5aa1fa27586b`.

Ein Feature-APK gilt erst als veröffentlicht, wenn APKTrack `status="published"` und `receipt_delivered=true` meldet.
Diese Anleitung sagt, was sich durchspielen lässt. Sie ist kein Nachweis, dass die Abläufe auf Android bestanden
wurden.

Belege bisher:
- native Tests und JVM-Tests;
- Prozesstests mit drei Teilnehmern über einen feindlichen Proxy;
- T2 gegen drei echte strfry-Relays auf 127.0.0.1.

Auf einem Gerät noch nicht abgenommen: Bluetooth, NSD, Kamera, Amber im Hintergrund und Doze.

## 1. Vorbereitung

1. Feature-APK installieren und ein Konto wählen. Ohne Amber genügt „Lokaler Schlüssel“ mit einer eigenen npub nur
   für den Test.
2. Einstellungen → CruxCoach-Konto → „Vorschau: Persönliche Daten teilen“ einschalten, dann „Persönliche Daten
   teilen“ öffnen.
3. Die Frage „Bist du 16 oder älter?“ beantworten. Bei „unter 16“ bleibt Teilen aus.
4. Für das Treffen: WLAN und Bluetooth an, beide Apps offen.

**Briefkästen:** Bis der Owner entscheidet, welche Briefkästen ein Test nutzen darf (F6), nutzt die Vorschau nur den
CruxCoach-Briefkasten, und der ist noch nicht in Betrieb. Deshalb geht heute nur der direkte Weg (QR-Code, WLAN,
Bluetooth). Einladungslinks und Teilen über verschiedene Netze folgen mit F6.

## 2. Abläufe

| # | Was | Wie | Erwartet |
|---|---|---|---|
| 1 | Einladung per QR-Code | A: „QR-Code zeigen“. B: „QR-Code scannen“, Kamera erlauben, auf A halten | B zeigt „Mit npub1… verbinden?“ mit derselben Kennung, die unter A’s Code steht. Nach „Anfrage senden“ steht bei A unter „Anfragen“ B’s Kurz-npub. |
| 2 | Annehmen | A: Vorlage wählen; „Auch meine Daten teilen“ steht auf aus; „Annehmen“ | Verbunden. A empfängt nur, bis A das Teilen für B einschaltet. |
| 3 | Daten kommen an | B: eine Begehung eintragen; beide Apps offen nebeneinander | Bei A steht auf B’s Seite „Aktuell · Stand heute …“; die Begehung erscheint. |
| 4 | Einschränken | B: auf A’s Seite eine Kategorie ausschalten oder „Nie teilen“ an einem Eintrag | Nach dem nächsten Abgleich ist es bei A weg. |
| 5 | Nur direkt | „Nur direkt“ einschalten | „Einladungslink erstellen“ verschwindet. Auf der Personenseite steht „Abgleich nur bei direktem Kontakt“. Die Verbindungsseite listet nur WLAN und Bluetooth. |
| 6 | Verbindung | „Verbindung“ öffnen | Ein Zustand statt einer Fehlermeldung („Verbunden“, „Offline – wird nachgeliefert“). Je Weg der Zustand und „zuletzt …“; offene Lücken je Freund. |
| 7 | Beenden | A: „Freundschaft beenden“ | Bei B sind A’s Daten weg, sobald B’s App die Nachricht bekommt. |

**Nach F6** (Briefkästen freigegeben) kommt hinzu:
- „Einladungslink erstellen“ und über einen Messenger senden; der Empfänger öffnet den Link, der Aussteller bestätigt;
- Teilen in verschiedenen Netzen;
- ein Telefon einen Tag aus, danach „vollständig bis …“.

## 3. Was nicht dazugehört

- Mehr als 32 Freunde: Das kommt mit Verteilern ab 0.2.7.
- Eigene Briefkästen und Tor.
- Release-Builds: Dort gibt es den Schalter nicht, und `/f/`-Links öffnen die App ohne Einladung.
