# Mayo TV Core

**Die Kernbibliothek von [Mayo TV](https://github.com/Jonathanxoxox/tvplayer)**, des IPTV-Players für Android TV.

Hier steckt alles ohne Oberfläche: Datenbank, Playlists (M3U, Xtream, Stalker), EPG, Sync, Sicherung,
Profile, Downloads, Einstellungen, Metadaten und sämtliche Texte (`:core`) sowie die Wiedergabe mit
libmpv und Media3/ExoPlayer (`:player-core`). Die App baut diesen Fork immer aus dem Quelltext: der
Branch `mayo` ist der Stand, den jede Mayo-TV-APK enthält.

> **Hinweis:** Mayo TV ist nur ein Player. Es bringt keine Sender, Playlists oder Streams mit; wer
> die App nutzt, ist für die Quellen, die er hinzufügt, selbst verantwortlich.

## Wie die App den Core bekommt

- `core-fork.properties` in [tvplayer](https://github.com/Jonathanxoxox/tvplayer) nennt Repository und
  Branch (`repo=Jonathanxoxox/OwnTV_Core`, `ref=mayo`).
- Jeder Workflow dort, der Gradle startet, holt den Branch mit `tools/ci/core-fork.sh` und baut die App per
  Composite-Build dagegen (`owntv.corePath`). Ohne diesen Pfad bricht der App-Build ab.
- Lokal: `owntv.corePath=C:/Pfad/zu/OwnTV_Core` in `~/.gradle/gradle.properties`.

Eine Core-Änderung kommt also ohne Veröffentlichung in die App: auf `mayo` pushen, App-Build anstoßen.

## libmpv

`:player-core` braucht `tv.own.owntv:libmpv`. Die Versionen liegen im eigenen Maven-Verzeichnis dieses
Forks (Branch `gh-pages`) und werden direkt von GitHub gelesen:

```
https://raw.githubusercontent.com/Jonathanxoxox/OwnTV_Core/gh-pages/maven
```

## Module

| Modul | Paket | Inhalt |
|---|---|---|
| `:core` | `tv.own.owntv.core` | Room-Datenbank und Migrationen, Sync, Parser, EPG, Sicherung, Profile, Downloads, Einstellungen, Metadaten, alle Texte |
| `:player-core` | `tv.own.owntv.playercore` | Wiedergabe: libmpv, Übergabe an Media3/ExoPlayer, Fallback-Kette, Watchdogs, Live-Tuning |

Paketnamen, Maven-Koordinaten und Speichernamen (`owntv.db`, DataStore-Dateien) behalten bewusst ihre
OwnTV-Namen: Ein Umbenennen würde gespeicherte Daten, geplante Hintergrundjobs und Links installierter
Apps verlieren.

## Herkunft und Lizenz

Mayo TV Core ist eine veränderte Fassung von [OwnTV Core](https://github.com/ahXN00/OwnTV_Core)
(© ahXN00, GPLv3), dem Kern von [OwnTV](https://github.com/ahXN00/OwnTV). Geändert wird seit
Oktober 2026 für Mayo TV; der Verlauf steht in den Commits dieses Forks.

Veröffentlicht unter der **GNU General Public License v3.0** – siehe [LICENSE](LICENSE). Wer eine
veränderte Fassung weitergibt, muss sie ebenfalls unter der GPLv3 und mit Quelltext weitergeben.

Danke an die Projekte, auf denen der Core aufbaut: [libmpv / mpv](https://mpv.io/) (FFmpeg),
[libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android),
[Media3 / ExoPlayer](https://developer.android.com/media/media3), [Room](https://developer.android.com/jetpack/androidx/releases/room),
[Koin](https://insert-koin.io/), [OkHttp](https://square.github.io/okhttp/),
[Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) und
[WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager).
Film- und Seriendaten kommen von [TMDB](https://www.themoviedb.org/) (dieses Produkt nutzt die TMDB-API,
ist aber nicht von TMDB unterstützt oder zertifiziert), Untertitel von
[OpenSubtitles](https://www.opensubtitles.com/).
