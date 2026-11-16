# DocuCrawler für Android

Native Android-App (Kotlin, Jetpack Compose, Material 3, MVVM), die ganze Wikis und Dokumentationsseiten
rekursiv crawlt, in sauberes Markdown umwandelt und als ZIP oder `combined_wiki.md` für Claude exportiert –
auch hinter Logins (Formular, SSO, OAuth, 2FA).

## Ablauf

1. **Setup**: Start-URL, Projektname, Tiefe, Delay (Standard 500 ms), parallele Verbindungen (1–16, Standard 4)
   und optional einen CSS-Selektor (`main`, `article`, `#mw-content-text`, …) eingeben.
   Der Delay gilt pro Verbindung – Durchsatz ≈ Verbindungen / (Delay + Antwortzeit).
2. **Login (optional)**: „Im Browser einloggen“ öffnet eine WebView (JavaScript + DOM-Storage aktiv,
   Third-Party-Cookies für SSO erlaubt). Nach dem Login übernimmt **„Login abschließen & Session übernehmen“**
   alle Cookies per `CookieManager.getInstance().getCookie(url)` sowie den User-Agent.
   Geschützte Bereiche mit **HTTP-Authentifizierung** (Basic Auth / `.htaccess`, Browser-Dialog „Sign in“)
   werden über einen eigenen Anmeldedialog bedient; der Crawler sendet die Zugangsdaten anschließend als
   `Authorization: Basic …` – ausschließlich an denselben Host und nur im Arbeitsspeicher gehalten.
3. **Crawler**: Ein Foreground-Service (`dataSync`, Partial-WakeLock) crawlt per BFS weiter, auch bei
   ausgeschaltetem Display. Das Dashboard zeigt live gespeicherte Seiten, Warteschlange, Fehler, aktuelle URL,
   Log und einen Abbrechen-Button (auch in der Benachrichtigung).
4. **Export**: ZIP des Projektordners (optional inkl. `combined_wiki.md`) oder nur die zusammengeführte
   Markdown-Datei. Danach öffnet sich direkt das Android-Share-Sheet (`ACTION_SEND`), z. B. zur Claude-App.

## Architektur

```
app/src/main/java/dev/docucrawler/android/
├── crawler/                 # Engine ohne Android-Abhängigkeit (JVM-testbar)
│   ├── WikiCrawler.kt       # BFS, Scope-Filter, Retry/429, Redirect-Erkennung, Dateiablage
│   ├── MarkdownConverter.kt # Jsoup-Cleanup + CSS-Selektor + flexmark-html2md
│   ├── UrlUtils.kt          # Normalisierung, Scope, URL → Dateipfad
│   ├── CookieProvider.kt    # Abstraktion des Cookie-Stores
│   └── WebViewCookieProvider.kt  # Bridge zu android.webkit.CookieManager
├── data/                    # SessionRepository, CrawlStateRepository (StateFlow), SettingsStore
├── service/WikiCrawlerService.kt  # Foreground-Service
├── export/                  # Exporter (ZIP/combined), ProjectStorage, ShareHelper (FileProvider)
└── ui/                      # MainViewModel, NavHost, Screens (Setup, Login, Dashboard, Export), Theme
```

- **Session**: Ein OkHttp-*Network*-Interceptor setzt bei jedem Request (auch jedem Redirect-Hop) den
  `Cookie`-Header live aus dem `CookieManager` und schreibt `Set-Cookie`-Antworten zurück – erneuerte
  Session-Cookies gehen also nicht verloren. Der beim Login erfasste Snapshot dient als Fallback.
- **Scope**: gleicher Host und – standardmäßig – nur unterhalb des Pfad-Präfixes der Start-URL
  (`/wiki/Main_Page` → `/wiki/`). Binärdateien und Ausschluss-Muster (u. a. **Logout-Links**, `action=edit`,
  `Special:`) werden nie abgerufen.
- **Ablage**: `filesDir/crawls/<projekt>/` exakt nach URL-Pfad, z. B. `/docs/guide/` → `docs/guide/index.md`,
  `/docs/page.html` → `docs/page.md`. Jede Datei hat YAML-Front-Matter (`title`, `source`, `crawled_at`).
- **Markdown**: Scripts/Styles/Formulare werden entfernt, Links/Bilder absolut gemacht, `<pre>`-Blöcke zu
  Fenced-Code mit erkannter Sprache normalisiert, Tabellen als GFM-Tabellen ausgegeben.

## Bauen

Voraussetzungen: JDK 17+, Android SDK (Platform 35).

```bash
./gradlew testDebugUnitTest   # JVM-Tests: Crawler (MockWebServer), Konverter, URL-Mapping, Export
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

## Grenzen

- Inhalte, die erst per JavaScript gerendert werden (reine SPAs), sieht der Jsoup-Crawler nicht.
- Sessions, die ausschließlich über `Authorization`-Header/LocalStorage-Tokens laufen (statt Cookies),
  werden nicht übernommen.
- Manche Anbieter (z. B. Google-Login) blockieren eingebettete WebViews trotz angepasstem User-Agent.
- Android 15 begrenzt `dataSync`-Foreground-Services auf 6 h pro Tag; danach wird sauber abgebrochen.
