# Myco/FIPS für CruxCoach: technische Bewertung und Handlungsempfehlung

**Stand:** 12. August 2026
**Ergebnis in einem Satz:** Myco/FIPS sollte derzeit nicht als Ganzes in CruxCoach eingebaut werden; die wertvollsten Konzepte — kryptografische Geräteidentität, authentifizierte Einladungen, versionierte Nearby-Nachrichten, Trennung von Discovery und Datentransport sowie signierte Manifeste mit content-addressed Blobs — lassen sich kleiner und zielgenauer in die vorhandene Architektur übernehmen.

## 1. Entscheidungsvorlage

| CruxCoach-Anwendungsfall | Empfehlung | Begründung |
| --- | --- | --- |
| Online-App-Share per QR/Zapstore | **Beibehalten** | Der bestehende langlebige Download-Link und der signierte, content-addressed Zapstore-Pfad passen besser zur APK-Verteilung als Mycos nsite-Modell. |
| Offline-APK und Board-DB an eine Person in der Nähe | **Wi-Fi Direct/LocalOnlyHotspot beibehalten und härten** | Das ist ein expliziter, kurzlebiger Host-zu-Empfänger-Transfer. Genau dafür ist die vorhandene Sternverbindung passend; ein komplettes Mesh bringt hier mehr Komplexität als Nutzen. |
| Nearby-Erkennung am Board | **Myco/FIPS-Konzepte übernehmen, FIPS selbst noch nicht** | BLE sollte nur Anwesenheit und Fähigkeiten ankündigen. Identität, Zustimmung und zustandsändernde Befehle gehören in eine authentifizierte Sitzung. |
| Gemeinsame Session Queue am Board | **Optional „offen“ oder „Invite-only“ anbieten und beide Modi klar kennzeichnen** | Der heutige offene Modus ist funktional nachvollziehbar, erlaubt aber jedem kompatiblen Gerät in Reichweite nach `JOIN` die Queue und teilweise den Board-Ablauf zu steuern. |
| Persistentes Gym-Mesh, Multi-Hop, Transportwechsel, ein Online-Gerät versorgt viele Offline-Geräte | **Isolierter FIPS-Pilot** | Erst hier rechtfertigen Multi-Hop-Routing, stabile npub-Adressierung und der Wechsel zwischen BLE/Wi-Fi den Integrationsaufwand. |
| Myco-nsite-Runtime als App-Store-Ersatz | **Nicht übernehmen** | Myco verteilt signierte statische Web-Apps, keine Android-APKs. Das löst ein anderes Produktproblem. |

Meine klare Empfehlung ist daher: **Phase 0 und 1 aus Abschnitt 8 umsetzen; FIPS nur als getrennten Proof of Concept für ein tatsächlich gewünschtes Gym-Mesh untersuchen.**

## 2. Was Myco und FIPS tatsächlich sind

### 2.1 Myco ist kein APK-Nearby-Share

Bei Myco ist eine „App“ ein **nsite**: eine statische Web-App mit einem vom Autor signierten Nostr-Manifest und über SHA-256 adressierten Blossom-Dateien. Installation bedeutet, diese Inhalte lokal zu synchronisieren und offline in einer WebView bereitzuhalten. Weitergabe bedeutet, dieselben signierten Inhalte erneut anzubieten. Das ist architektonisch elegant, aber nicht mit dem Verteilen einer signierten Android-APK gleichzusetzen.

Die Hauptkomponenten sind:

```text
Android-App
  ├─ UI, QR/NFC, BLE, Wi-Fi Aware, VpnService/TUN
  └─ JNI
       └─ myco-core (Rust)
            ├─ FIPS: verschlüsseltes Live-Mesh
            ├─ eingebettetes Nostr-Relay
            ├─ Blossom-Server
            └─ nsite-deck: Manifest-, Blob- und Sync-Logik
```

Myco trennt sinnvoll zwischen:

- **Transport:** FIPS routet verschlüsselte Pakete über einen momentan vorhandenen Pfad.
- **Store-and-forward:** Mycos Relay-/nsite-Schicht speichert signierte Manifeste und Blobs und kann sie später an andere Geräte weiterreichen. FIPS selbst puffert keine Nachricht bis ein heute nicht erreichbarer Empfänger morgen wieder auftaucht.

Diese Grenze ist für CruxCoach wichtig: Für einen Offline-Katalogtausch brauchen wir nicht automatisch ein IP-Mesh. Signierte Manifeste und verifizierte Chunks können auch über den vorhandenen lokalen HTTP-Transport ausgetauscht werden.

Quellen: [Myco README am untersuchten Commit](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/README.md), [Architektur](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/docs/design/architecture.md), [Propagation-Design](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/docs/design/propagation.md).

### 2.2 Was FIPS hinzufügt

FIPS ist ein allgemeines verschlüsseltes IPv6-Mesh mit Nostr-/secp256k1-Geräteidentitäten. Relevante Eigenschaften:

- mehrere Transportarten unter einer stabilen Geräteadresse;
- Multi-Hop-Routing über aktuell erreichbare Knoten;
- Noise IK für verschlüsselte und authentifizierte Links sowie Noise XK für Ende-zu-Ende-Sitzungen;
- ChaCha20-Poly1305, HKDF-SHA256, periodisches Rekeying und Replay-Fenster;
- Android-Einbettung als Rust-Bibliothek; die Host-App stellt unter anderem den TUN über `VpnService` bereit;
- BLE L2CAP CoC für die Kontroll-/Fallback-Strecke sowie bei Myco Wi-Fi Aware für schnellere lokale Übertragung.

Das ist erheblich mehr als „Nearby Share“. Es bringt Rust/Tokio, JNI, eine zusätzliche Identität, Radio-Orchestrierung, ein virtuelles Netz, Hintergrund-/Foreground-Service-Lifecycle und eine deutlich größere Testmatrix in die Android-App.

FIPS bezeichnet Protokoll und APIs selbst als noch nicht stabil. Der von Myco verwendete Fork steht auf `v0.5.0-dev`; Security Audit und eine einfachere native App-API sind noch Roadmap-Themen. Das spricht gegen eine produktionskritische Komplettintegration in CruxCoach zum jetzigen Zeitpunkt. Quellen: [FIPS README am von Myco verwendeten Commit](https://github.com/jmcorgan/fips/blob/967776079ba5ddc8fe118c3f289365b51eb03737/README.md), [FIPS Security Reference](https://github.com/jmcorgan/fips/blob/967776079ba5ddc8fe118c3f289365b51eb03737/docs/reference/security.md).

### 2.3 Android-Transporte und ihre Grenzen

Myco verwendet BLE **L2CAP CoC**, nicht GATT, für FIPS-Pakete. Das erfordert effektiv Android API 29. Die Myco-Dokumentation nennt ungefähr 22 KB/s und rund 15 Minuten für 20 MB über BLE. Eine 85-MB-Board-Datenbank läge linear hochgerechnet bei ungefähr 64 Minuten — BLE ist damit Discovery/Control/Fallback, nicht unser Bulk-Kanal.

Wi-Fi Aware ist die interessantere optionale Datenstrecke: Geräte entdecken sich ohne Access Point und errichten eine Network Data Path-Verbindung; Myco transportiert FIPS darüber per UDP/IPv6. Aber:

- API 29 ist die praktische Untergrenze dieses Myco-Pfads;
- nicht jedes Android-Gerät unterstützt Wi-Fi Aware und OEM-Verhalten variiert;
- reale Interoperabilität benötigt Tests mit mindestens zwei physischen Geräten;
- es gibt weiterhin bekannte Peering-Probleme.

Mycos Release Notes vom 9. August 2026 nennen ausdrücklich nicht zuverlässig verbindende Telefone, eine etwa einminütige Blockade nach Rotation einer Wi-Fi-Adresse, UI-Lag während der Synchronisation und einen noch nicht auf zwei Geräten verifizierten Deep-Link-End-to-End-Pfad. Quelle: [Myco v0.5.0 Release Notes](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/RELEASE-NOTES.md), [Wi-Fi-Aware-Design](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/docs/design/wifi-aware-interop.md), [BLE-Design](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/docs/design/ble-interop.md).

FIPS bündelt mehrere Links eines Peers und kann eine Session auf einen anderen Link umziehen. Das ist **kein gleichzeitiges Striping**, bei dem Teile derselben großen Datei parallel über BLE und Wi-Fi laufen.

Myco verwirft Wi-Fi Direct als **Designzentrum eines symmetrischen, dauerhaften Meshes**: Jede Gruppe wählt einen Group Owner, bildet damit einen Stern und fällt vollständig aus, wenn dieser Owner verschwindet. Das ist eine nachvollziehbare Entscheidung für Myco, aber kein Gegenargument gegen CruxCoachs heutigen Einsatz. Unser Transfer ist bewusst kurzlebig und asymmetrisch — ein Gerät hostet APK/DB, ein anderes lädt sie. Für genau diesen Fall ist die Group-Owner-Struktur nützlich und kein Architekturbruch. Wi-Fi Aware sollte deshalb ergänzen, nicht den bewährten Fallback verdrängen.

## 3. Gegenüberstellung mit dem aktuellen CruxCoach-Code

Untersucht wurde der lokale CruxCoach-Stand `baec996ab839444fb40312896cf8671aee92b182` auf `feat/0.2.2-release`.

### 3.1 App Share ist bereits sinnvoll aufgeteilt

`AppShareSection.kt` bietet vier Wege:

1. einen langlebigen Online-QR über `APP_SHARE_DOWNLOAD_URL` mit `https://cruxcoach.org/get.html` als Standard;
2. einen Offline-Hotspot über Wi-Fi Direct, mit LocalOnlyHotspot als Fallback, QR für WLAN-Zugang und lokalem HTTP-Server;
3. einen versionsgenauen Zapstore-Link aus signierten/content-addressed NIP-82-Metadaten;
4. das Android Share Sheet über `FileProvider`.

Der lokale Server liefert die installierte `CruxCoach.apk`, eine Landing Page und optional `/board.db`. Besonders gut gelöst sind:

- Bind an die konkrete Hotspot-IP statt an alle Interfaces (`ApkShareHelper.kt:195–208`);
- ein konsistenter DB-Snapshot aus Hauptdatei und WAL unter `BEGIN IMMEDIATE`;
- Falten des WAL auf einer privaten Kopie, Scrubbing privater Entwürfe/Audit-Felder und anschließendes `VACUUM`;
- niemals ein Fallback auf die rohe Live-Datenbank;
- `503` plus `Retry-After`, solange der Snapshot gebaut wird;
- fünf Minuten **Idle**-Timeout statt eines Timers, der eine laufende Übertragung abschneidet;
- Annahme des Import-Deep-Links nur für private/Loopback-IPv4-Ziele und eine Bestätigung in der App;
- ein restriktiver Importvertrag, der fremde Provenienz-, Account- und Auth-Felder nicht einfach übernimmt.

Das ist für den konkreten Ablauf „Person A gibt Person B jetzt APK und öffentliche Board-Daten“ bereits näher am Ziel als Myco/FIPS.

### 3.2 Härtungspotenzial beim lokalen Share

Im aktuellen lokalen HTTP-Protokoll fehlen noch einige Eigenschaften, die sich direkt aus Mycos Inhaltsmodell ableiten lassen:

- **Kein eigener Session-Zugriffsschutz:** Wer im temporären WLAN ist, kann `/CruxCoach.apk` und `/board.db` abrufen. Das WPA-Passwort ist zugleich Netz- und Datenzugang.
- **Kein Transfermanifest:** Größe, SHA-256, Schema-/Katalogversion und erwarteter APK-Signer-Fingerprint werden nicht zusammen angekündigt und auf Empfängerseite vor Aktivierung geprüft.
- **Kein Range/Resume:** Ein abgebrochener großer DB-Transfer beginnt erneut.
- **Keine Pfadbindung des Imports an den Einmalvorgang:** Der Deep Link enthält nur die lokale URL.

Empfehlung für `Local Share v2`:

```text
QR 1: WLAN-Zugang
QR 2 / Landing-URL:
  http://<private-ip>:<port>/s/<128-bit-random-token>/

GET manifest.json
  sessionId, expiresAt, protocolVersion
  apk: size, sha256, packageName, versionCode, signerCertSha256
  boardDb: size, sha256, schemaVersion, catalogueVersion

GET apk / board chunks
  Bearer oder nicht erratbarer URL-Token
  Range + ETag
  Hashprüfung vor Install/Import
```

Der Token sollte nach erfolgreichem Transfer, manuellem Stop oder Timeout ungültig werden. Für die Verifikation lassen sich vorhandene CruxCoach-Bausteine wie `IntegrityVerifier`, `UpdaterPinStore` und die Blossom-Manifest-/Chunklogik wiederverwenden. Bei einer APK-Aktualisierung schützt Android zusätzlich über die Signaturkontinuität; bei einer Erstinstallation sollte CruxCoach dennoch den erwarteten Publisher-Fingerprint sichtbar bzw. in einem eigenen Receiver-/Updaterpfad prüfbar machen.

### 3.3 Nearby am Board ist funktional, aber nicht authentifiziert

Die aktuelle BLE-Nachbarschaftsschicht in `NearbyClimbProtocol.kt` ist kompakt und pragmatisch, hat aber keinen expliziten Protokollversions-Byte, keine Signatur/MAC, keinen Nonce und keinen Replay-Schutz. Advertisements transportieren unter anderem:

- Climb-UUID und Winkel;
- Board-verbunden/Gone;
- Disconnect Request und Response;
- Session-ID, Teilnehmerzahl und Hostname.

Die Scanner- und Session-Pfade zeigen folgende Konsequenzen:

- `NearbyPresenceManager` startet den Low-Latency-Scan beim Erzeugen des Singletons; ein sichtbarkeits-/sessionabhängiger Duty Cycle wäre sparsamer.
- Rohzustand und Teilnehmerzuordnung verwenden BLE-Geräteadressen. Android kann diese Adressen randomisieren; sie sind keine belastbare Geräteidentität.
- `DisconnectRequest` und `DisconnectResponse` sind unauthentifizierte Advertisements. Ein Gerät in Reichweite kann sie imitieren oder in ein Rennen eingreifen.
- `SessionCommandGate` sagt explizit: `JOIN` ordnet Befehle, ist aber keine Authentifizierung.
- Die GATT-Characteristics verwenden `PERMISSION_READ`/`PERMISSION_WRITE`, nicht die Android-Permissions für verschlüsselte bzw. MITM-geschützte Links.
- Nach offenem `JOIN` kann ein kompatibler Client Queue-Einträge hinzufügen, entfernen und verschieben sowie Current/Next/Prev auslösen. Das kann über die Hostlogik bis zur physischen Board-Projektion wirken.
- Der Host begrenzt auf sieben GATT-Clients; die offene Aufnahme bleibt damit zusätzlich eine knappe Ressource.

Das muss nicht automatisch ein Bug sein: Eine offene Gym-Session kann genau das gewünschte Produktverhalten sein. Es sollte aber eine bewusste Vertrauensentscheidung sein und nicht nur aus dem Transport resultieren.

Zusätzlich verwendet CruxCoach `0xFFFF` und `0xFFFE` als BLE Company Identifier. Bluetooth SIG vergibt Company Identifiers eindeutig; diese ad-hoc Werte sind nicht CruxCoach zugeordnet und können kollidieren. Robuster wäre entweder ein eigener zugeteilter Company Identifier oder eine projektspezifische 128-Bit-Service-UUID, wobei die Detaildaten wegen des knappen Legacy-Advertisement-Budgets anschließend über GATT gelesen werden. Quelle: [Bluetooth SIG Assigned Numbers](https://www.bluetooth.com/specifications/assigned-numbers/).

## 4. Welche Myco/FIPS-Konzepte wir übernehmen sollten

### 4.1 Advertisement ist nur ein Hinweis

Ein BLE-Advertisement darf sagen: „Hier ist eine CruxCoach-Session mit ephemerer ID X; sie kann GATT v2 und lokalen Bulk-Transfer.“ Es sollte niemals allein einen Disconnect, eine Übernahme oder eine Board-Aktion autorisieren.

Nach dem Discovery-Hinweis folgt eine Verbindung, ein kryptografischer Handshake und erst dann ein zustandsändernder Befehl. Das beseitigt auch die problematische Kopplung an wechselnde BLE-MAC-Adressen.

### 4.2 Stabile, zweckgebundene Geräteidentität

Myco leitet seine Knotenadresse aus einem Geräte-Pubkey ab. Für CruxCoach ist das ebenfalls passend, aber der bestehende Nostr-Autor-/Backup-Schlüssel sollte nicht unreflektiert zugleich Nearby-Geräteidentität werden. Empfehlenswert ist:

- ein separater Device-Key im Android Keystore oder
- eine dokumentierte, domain-separierte Ableitung nur für Nearby, sofern Wiederherstellbarkeit wirklich gewünscht ist.

Der öffentliche Schlüssel ist die stabile Identität; BLE-Adresse und IP sind austauschbare Kontaktpunkte.

### 4.3 Einmal-Einladung plus Bestätigung

Ein QR-/NFC-Invite sollte mindestens enthalten:

```text
protocolVersion
inviterDevicePublicKey
ephemeralSessionId
128/256-bit oneTimeSecret
expiresAt
capabilities
```

Der Empfänger beweist in einem Challenge-Response-Handshake den Besitz des Secrets; beide Seiten binden ihre Device-Pubkeys und den Sitzungskontext in den Transcript ein. Danach wird ein AEAD-Sitzungsschlüssel abgeleitet. Für sensible Aktionen kann zusätzlich ein kurzer Safety Code auf beiden Displays verglichen werden.

Ein übertragener QR-Code ist grundsätzlich ein **Bearer Credential**: Wer ihn während seiner Gültigkeit zuerst einlöst, kann einen legitimen Empfänger ausstechen. „Single use“ verhindert den zweiten Einsatz, nicht das erste Race. Sichtbare Gegenstellenbestätigung und kurze Ablaufzeit bleiben deshalb wichtig.

### 4.4 Versionierter Nachrichtenumschlag

Nearby v2 sollte einen einheitlichen Umschlag haben:

```text
version | messageType | capabilityBits | sessionId
messageId | counter/nonce | ttl/expiresAt | encryptedPayload | authTag
```

Dadurch werden Evolution, Capability Negotiation, Duplikaterkennung, Replay-Abwehr und saubere Ablehnung unbekannter Versionen möglich. Kleine Advertisements enthalten nur einen verkürzten Discovery-Teil; vollständige Befehle laufen über die verbundene, authentifizierte GATT-/L2CAP-Sitzung.

### 4.5 „BLE entdeckt, Wi-Fi transportiert“

Für CruxCoach passt ein Transport-Interface besser als ein sofortiger Mesh-Einbau:

```text
NearbyTransport
  ├─ GattControlTransport       (klein, überall verfügbar)
  ├─ WifiDirectHttpTransport    (heutiger Bulk-Fallback)
  ├─ LocalOnlyHotspotTransport  (heutiger Bulk-Fallback)
  └─ WifiAwareTransport         (optional ab API 29)
```

Discovery und Schlüsselaufbau passieren über QR/NFC/BLE; danach wird der schnellste gemeinsam unterstützte Bulk-Kanal gewählt. Der Anwendungscode arbeitet mit Manifesten/Chunks und nicht mit SSID, PSM oder IP-Adressen.

### 4.6 Kleine signierte Manifeste, große Blobs nur auf Nachfrage

Dies ist die am besten passende Myco-Idee — und CruxCoach ist durch Nostr/Blossom bereits darauf vorbereitet:

- kleine signierte Metadaten verbreiten;
- große DB-Chunks nur bei Bedarf ziehen;
- jeden Chunk per SHA-256 prüfen;
- vorhandene Chunks nicht erneut senden;
- erst vollständig laden und verifizieren, dann atomar aktivieren;
- vorherige bekannte Version für Rollback behalten.

Für Board-Daten sollte der Peer nicht blind eine monolithische DB übertragen, wenn dem Empfänger nur wenige Chunks fehlen. Der vorhandene Blossom-Manifestpfad kann um eine lokale Peer-Quelle erweitert werden; die Vertrauensentscheidung bleibt im signierten Manifest, nicht im Transport.

### 4.7 Diagnostik als Produktfunktion

Mycos v0.5.0 investiert bewusst in Peer-/Radio-Diagnostik. Für CruxCoach wären hilfreich:

- entdeckte Gegenstelle und verwendeter Kanal;
- Handshake-/Ablehnungsgrund, Protokollversion und Fähigkeiten;
- Zeit bis Discovery/Connect, RSSI, Durchsatz und Abbruchgrund;
- Wechsel/Fallback zwischen GATT, Wi-Fi Aware und Wi-Fi Direct;
- begrenzte persistente Ereignishistorie ohne Secrets oder private Nutzdaten.

Das ist besonders bei OEM-spezifischem Android-BLE-Verhalten wichtiger als eine komplexe automatische Heuristik ohne Sichtbarkeit.

## 5. Was wir nicht blind aus Myco übernehmen sollten

### 5.1 Pairing-Dokumentation und Implementierung weichen ab

Mycos Design beschreibt den Einmal-Secret-Handshake als zwingende, gegenseitige Bindung. Im untersuchten Code ist das differenzierter:

- `PairSecrets.consume()` prüft das Secret nur im Android-Pfad für **Auto-Accept während Präsentation**.
- `myco-core::accept_pair_request(npub, name)` akzeptiert anhand der ausgewählten Pending Request; es validiert selbst kein Secret.
- Der „Share an app“-Bildschirm erzeugt mit `NsiteShare.newPairSecret()` ein Secret und reicht den fertigen URI an `PairPresent.beginRaw()`. Dieser Pfad registriert das Secret nicht über `PairSecrets.issue()`; damit kann er nicht denselben Auto-Accept-Nachweis führen und fällt praktisch auf den manuellen Bestätigungsdialog zurück.
- Ein Unpair ist im Core als best-effort/fire-once dokumentiert; ein gerade offline befindlicher Peer kann einen veralteten Circle-Eintrag behalten.

Das ist nicht zwingend unsicher, wenn die manuelle Bestätigung als maßgebliche Vertrauensgrenze verstanden wird. Es ist aber ein Grund, das Design nicht ungeprüft zu kopieren und die behaupteten Garantien exakt gegen alle UI-Pfade zu testen. Quellen: [PairSecrets.kt](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/android/app/src/main/java/app/myco/share/PairSecrets.kt), [MycoApp.kt](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/android/app/src/main/java/app/myco/ui/MycoApp.kt), [AppsScreen.kt](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/android/app/src/main/java/app/myco/ui/screens/AppsScreen.kt), [content.rs](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/myco-core/src/content.rs), [Pairing-Design](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/docs/design/identity-pairing.md).

### 5.2 VpnService/TUN ist für CruxCoach ein hoher Preis

Myco macht herkömmliche IP-Dienste über `<npub>.fips` und ein virtuelles IPv6-Netz erreichbar. Dafür besitzt die Android-App einen `VpnService`/TUN. Das ist für einen allgemeinen Mesh-Host nachvollziehbar, für CruxCoach aber wahrscheinlich unnötig:

- Android erlaubt normalerweise nur einen aktiven VPN-Anbieter pro Nutzerprofil;
- der Nutzer sieht eine VPN-/Foreground-Service-Lebensdauer;
- Lifecycle, Doze und Prozessneustarts werden Teil des kritischen Board-Pfads;
- es entsteht eine zusätzliche Netzwerk-Angriffsfläche.

Wenn ein FIPS-Pilot erfolgt, sollte er möglichst eine native `device-key:port`-API verwenden und nicht zuerst den VPN-Slot zum Produktbestandteil machen.

### 5.3 Wi-Fi Aware ist optional, nicht alleinige Basis

CruxCoach 0.2.2 unterstützt aktuell ab API 26; die nächste geplante Mindestversion ist laut Build-Kommentar API 28. Mycos relevante Android-Lanes beginnen effektiv bei API 29. Ein Einbau als Pflichtpfad würde also zusätzliche Geräte ausschließen. Als optionaler Fast Path mit Fallback ist Wi-Fi Aware hingegen sinnvoll.

### 5.4 Supply Chain und Reproduzierbarkeit

Mycos CI- und Release-Workflows klonen den FIPS-Fork `jmcorgan/fips` vom veränderlichen Branch `feat/platform-peer-queue`; Cargo verwendet anschließend den lokalen Pfad `reference/fips`. Damit kann derselbe Myco-Commit zu unterschiedlichen FIPS-Quellen gebaut werden. Für einen CruxCoach-Pilot müssen mindestens gelten:

- exakter FIPS-Commit statt Branch-HEAD;
- überprüfte Lockfiles und reproduzierbarer Android-Build;
- Lizenzhinweise und SBOM für Rust-/Native-Abhängigkeiten;
- dokumentierter Update-/Security-Response-Prozess;
- Review der JNI- und `unsafe`-Grenzen.

Myco und FIPS stehen unter MIT, CruxCoach unter GPLv3; eine Nutzung ist grundsätzlich möglich, wenn Notices und die konkrete Distributionsform korrekt behandelt werden. Das ersetzt keine abschließende Lizenzprüfung. Quellen: [Myco CI](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/.github/workflows/ci.yml), [Myco Release Workflow](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/.github/workflows/release.yml), [Myco-Lizenz](https://github.com/Origami74/myco/blob/85316faf80fda48bfef8977584ab4ad68203de02/LICENSE), [FIPS-Lizenz](https://github.com/jmcorgan/fips/blob/967776079ba5ddc8fe118c3f289365b51eb03737/LICENSE).

### 5.5 Dokumentationsreife nicht mit Produktreife verwechseln

Das oberste Myco README sagt am untersuchten Commit noch „Design phase — not yet built“, während Release Notes und Quellcode v0.5.0 beschreiben. Mehrere Design-Dokumente sprechen weiterhin im Proposal-/Future-Tense. Diese Dokumentationsdrift und die offen genannten Hardwareprobleme bedeuten: Der Code ist eine wertvolle Referenz und ein guter Forschungsprototyp, aber keine fertige, verlässlich spezifizierte Android-Nearby-Bibliothek.

## 6. Vorgeschlagenes CruxCoach-Sicherheitsmodell

Es sollten drei Vertrauensstufen sichtbar getrennt werden:

| Modus | Discovery | Aufnahme | Erlaubte Aktionen |
| --- | --- | --- | --- |
| **Presence only** | öffentliches BLE-Advert | keine Verbindung nötig | Climb/Board/Session nur anzeigen; keinerlei Zustandsänderung |
| **Open gym session** | öffentlich | verschlüsselter Handshake, Nutzer tritt bewusst bei | Queue-Steuerung gemäß Host-Policy; Rate Limits und Kick/Ban |
| **Invite-only** | öffentlich oder verborgen | QR/NFC-Einmal-Invite + gegenseitige Schlüsselbindung + Host-Bestätigung | Freigegebene Control- und Transferfähigkeiten |

Wichtig ist: „offen“ heißt nicht „unverschlüsselt und ohne stabile Gegenstelle“. Auch eine offene Session kann einen ephemeren Host-Schlüssel verwenden, die Verbindung verschlüsseln, Replay verhindern und pro Teilnehmer Rate Limits anwenden. Offen ist dann die Zulassungspolitik, nicht die technische Integrität.

Für Board-kritische Aktionen sollte der Host Fähigkeiten getrennt vergeben, zum Beispiel:

- `VIEW_SESSION`
- `ADD_REMOVE_OWN_ITEMS`
- `REORDER_QUEUE`
- `CONTROL_PLAYBACK`
- `REQUEST_BOARD_HANDOVER`
- `FETCH_PUBLIC_CATALOGUE`

So muss ein Teilnehmer, der nur einen Climb hinzufügen darf, nicht automatisch Next/Prev oder Board-Handover auslösen können.

## 7. Wann sich FIPS für CruxCoach wirklich lohnt

FIPS lohnt sich erst, wenn mindestens eines dieser Produktziele verbindlich ist:

1. **Multi-Hop im Gym:** Smartphone A sieht das Board, B sieht A, C sieht nur B; C soll trotzdem die Session erreichen.
2. **Transport-Roaming:** Eine langlebige Peer-Sitzung soll ohne Neuanmeldung zwischen BLE, LAN und Wi-Fi Aware wechseln.
3. **Dezentrales Gym-Cluster:** Geräte stellen signierte Kataloge/Playlists für andere bereit, auch wenn der ursprüngliche Publisher nicht mehr vor Ort ist.
4. **Generischer IP-Dienst im Mesh:** Mehr als CruxCoach-eigene Nachrichten sollen über stabile Geräteadressen erreichbar sein.

Für „zwei Personen stehen am selben Board“ sind diese Eigenschaften nicht nötig. Dort ist ein kleines authentifiziertes Protokoll über den vorhandenen GATT-Kanal plus Wi-Fi-Bulkpfad wesentlich günstiger.

## 8. Umsetzungsvorschlag

### Phase 0 — Nearby v2 und Local Share v2

Ziel: aktuelle Risiken schließen, ohne Rust/FIPS einzubauen.

1. Kurze Protokollspezifikation für versionierte Discovery-, Session- und Handover-Nachrichten schreiben.
2. Advertisements auf Hinweise reduzieren; Disconnect/Handover nur in verbundener Session zulassen.
3. Zweckgebundenen Device-Key und Challenge-Response-/AEAD-Sitzung einführen.
4. `Open gym` und `Invite-only` als explizite Host-Policy anbieten.
5. BLE-Identifier auf eine Projekt-Service-UUID mit Detailabruf über GATT oder einen zugeteilten Company Identifier umstellen; Übergangsdecoder für v1 behalten.
6. Scanner nach UI-/Board-/Session-Lifecycle duty-cyclen.
7. Lokalen Share mit Einmal-Token, Manifest, SHA-256, APK-Signer-Fingerprint, Range/ETag und Ablaufzeit härten.

### Phase 1 — Peer-lokaler Board-/Playlist-Sync

Ziel: große Inhalte effizient und transportsicher weitergeben.

1. Vorhandenes Blossom-/Board-Manifest als gemeinsame Inhaltsbeschreibung verwenden.
2. Peer-HTTP als weitere Chunk-Quelle hinter einer `NearbyTransport`-/`ContentSource`-Abstraktion ergänzen.
3. Nur fehlende Hashes abrufen; vollständig verifizieren; atomar aktivieren; Rollback behalten.
4. Wi-Fi Aware ab API 29 als optionalen Fast Path prototypisieren, Wi-Fi Direct/LocalOnlyHotspot als Fallback behalten.
5. Diagnosemetriken und Zwei-Geräte-Instrumentation hinzufügen.

### Phase 2 — isolierter FIPS-Pilot

Nur beginnen, wenn das Multi-Hop-/Gym-Cluster-Ziel bestätigt ist.

1. FIPS auf Commit `967776079ba5ddc8fe118c3f289365b51eb03737` oder einen bewusst geprüften Nachfolger pinnen.
2. Pilot in einem separaten Modul/Flavor halten; kein kritischer Board-Pfad und kein erzwungener `VpnService` im ersten Schritt.
3. Zuerst kleine verschlüsselte Queue-/Presence-Nachrichten, dann Wi-Fi-Aware-/AP-Bulk testen.
4. Mindestens folgende Matrix messen: Pixel, Samsung und ein weiterer OEM; Android 10 bis aktuell; Bildschirm an/aus; App Vorder-/Hintergrund; gleichzeitig aktive Board-BLE-Verbindung.
5. Nur bei nachgewiesenem Mehrwert in die Produktarchitektur übernehmen.

### Go/No-Go-Kriterien für den FIPS-Pilot

- mindestens 95 % erfolgreiche Zwei-Geräte-Verbindungen über die definierte Gerätematrix;
- mediane Discovery-/Handshake-Zeit unter 5 Sekunden im Vordergrund;
- keine Regression der Board-BLE-Verbindung oder Session Queue;
- definierter Batteriebedarf über eine reale Gym-Session;
- reproduzierbarer, commit-gepinnter Build und bestandener Dependency-/Security-Review;
- verständliche Nutzerführung für Bluetooth, Nearby Wi-Fi, Foreground Service und gegebenenfalls VPN;
- belastbarer Vorteil gegenüber dem kleineren Nearby-v2-Ansatz, insbesondere bei einem gemessenen Multi-Hop-Szenario.

## 9. Offene Produktentscheidungen für unsere Diskussion

1. **Was ist das eigentliche Zielbild?** Meine Annahme ist „spontane Zusammenarbeit direkt an einem Board“, nicht „dauerhaftes, mehrhopfiges Gym-Netz“. Falls das zweite Ziel strategisch wichtig ist, wird FIPS deutlich interessanter.
2. **Sollen veröffentlichte Sessions absichtlich für jeden in Reichweite steuerbar sein?** Ich empfehle einen klar markierten offenen Modus plus standardmäßig Invite-only für private/Homewall-Sessions.
3. **Was soll Nearby übertragen?** Meine Priorität wäre: Presence → Session/Queue → Playlist/fehlende Katalog-Chunks. Die APK bleibt ein separater, expliziter App-Share-Vorgang.
4. **Ist API 28 weiter relevant?** Falls ja, darf Wi-Fi Aware nur optional sein; Mycos API-29-Annahme kann nicht zur Basis werden.
5. **Wäre der Android-VPN-Slot akzeptabel?** Meine Empfehlung ist nein, solange CruxCoach keinen generischen Mesh-IP-Zugang benötigt.

## 10. Untersuchungsumfang und Grenzen

Geprüft wurden:

- Myco `85316faf80fda48bfef8977584ab4ad68203de02` (Tag/Release v0.5.0, 9. August 2026);
- der von Myco verwendete FIPS-Fork `jmcorgan/fips`, Branch `feat/platform-peer-queue`, Commit `967776079ba5ddc8fe118c3f289365b51eb03737`;
- Architektur-, Security-, BLE-, Wi-Fi-Aware-, Pairing- und Propagation-Dokumente;
- Rust-/Kotlin-Implementierung der relevanten Pfade sowie CI-/Release-Workflows;
- der aktuelle lokale CruxCoach-App-Share-, Import-, Nearby-, GATT- und Blossom-Code.

Dies ist ein statischer Code-/Architekturreview. Es wurden keine Myco/FIPS-Hardwaretests durchgeführt. Im lokalen Umfeld war kein `cargo` installiert, deshalb wurden die Rust-Tests nicht ausgeführt. Aktuelle GitHub-Actions-Ergebnisse wurden nicht als Beweis für Funktionalität gewertet. Die öffentlichen Repositories wurden commit-genau geklont, nachdem die verbundene GitHub-App für diesen Zugriff eine erneute Anmeldung verlangte.

### Relevante lokale CruxCoach-Einstiegspunkte

| Thema | Quellcode |
| --- | --- |
| App-Share-Oberfläche und Transportauswahl | [`AppShareSection.kt`](../../androidApp/src/main/java/com/cruxcoach/android/ui/settings/AppShareSection.kt) |
| Lokaler APK-/DB-Server und sicherer Snapshot | [`ApkShareHelper.kt`](../../androidApp/src/main/java/com/cruxcoach/android/util/ApkShareHelper.kt) |
| Wi-Fi Direct und LocalOnlyHotspot | [`WifiDirectHotspot.kt`](../../androidApp/src/main/java/com/cruxcoach/android/util/WifiDirectHotspot.kt) |
| Lokaler Import-Deep-Link | [`MainActivity.kt`](../../androidApp/src/main/java/com/cruxcoach/android/MainActivity.kt) |
| Semantische DB-Importgrenze | [`BoardDatabaseImporter.kt`](../../androidApp/src/main/java/com/cruxcoach/android/data/BoardDatabaseImporter.kt) |
| BLE-Advertisement-Format | [`NearbyClimbProtocol.kt`](../../androidApp/src/main/java/com/cruxcoach/android/ble/NearbyClimbProtocol.kt) |
| Scan, Staleness und Dedup | [`NearbyClimbScanner.kt`](../../androidApp/src/main/java/com/cruxcoach/android/ble/NearbyClimbScanner.kt) |
| Offene Session-Policy und Board-Wirkung | [`SessionGattBridge.kt`](../../androidApp/src/main/java/com/cruxcoach/android/data/SessionGattBridge.kt) |
| GATT-Permissions und Client-Limit | [`SessionGattServer.kt`](../../androidApp/src/main/java/com/cruxcoach/android/ble/SessionGattServer.kt) |
| JOIN-Gate | [`SessionCommandGate.kt`](../../androidApp/src/main/java/com/cruxcoach/android/ble/SessionCommandGate.kt) |
| Content-addressed Board-Manifeste/Chunks | [`BlossomManifest.kt`](../../androidApp/src/main/java/com/cruxcoach/android/data/blossom/BlossomManifest.kt), [`BlossomSyncManager.kt`](../../androidApp/src/main/java/com/cruxcoach/android/data/blossom/BlossomSyncManager.kt) |
| APK-Vertrauen/Publisher-Pinning | [`IntegrityVerifier.kt`](../../androidApp/src/main/java/com/cruxcoach/android/updater/IntegrityVerifier.kt), [`UpdaterPinStore.kt`](../../androidApp/src/main/java/com/cruxcoach/android/updater/UpdaterPinStore.kt) |
