# AAIdrive „gmap" — kontekst projektu dla Claude Code

## Cel
Natywna Google Maps + turn-by-turn na ekranie iDrive w BMW G30 530e (2017), system NBT EVO ID6, sterowana z auta przez Bluetooth (split / Widescreen OFF). Telefon: Samsung Galaxy S23 Ultra. Auto ma fabryczne bezprzewodowe CarPlay, BRAK Android Auto. Budujemy własny, ukryty flavor „gmap" projektu AAIdrive + własny turn-by-turn.

Użytkownik: Paweł, architekt. **Odpowiadaj po polsku, zwięźle, konkretnie, wykonawczo. Pojedyncze wartości, nie widełki. Szczerze o ograniczeniach.**

## Infrastruktura
- Fork: `github.com/PoweltTKH/AAIdrive`, gałąź `main`. Commitujemy bezpośrednio na `main`.
- Build: GitHub Actions `.github/workflows/build-gmap.yml` (ręczny, `workflow_dispatch`) → `assembleGmapNonalyticsFullDebug`, artefakt `aaidrive-gmap-apk`, ~5 min.
- Sekrety CI: `ANDROIDAUTOIDRIVE_GMAPSAPIKEY`, `ANDROIDAUTOIDRIVE_SPOTIFYAPIKEY`, `DEBUG_KEYSTORE_BASE64`.
- Google Cloud „aaidrive-maps": Maps SDK Android, Places, Directions, Geocoding.
- Spotify: package `me.hufman.androidautoidrive`, SHA-1 `A1DA07ADDEA5C6C5C4B36D31F7BADF7C8F08BE34`.

## Ścieżki
- Kod gmap: `app/src/gmap/java/me/hufman/androidautoidrive/carapp/maps/`
- Layout gmap: `app/src/gmap/res/layout/`
- Współdzielone: `app/src/main/...`
- Build: `app/build.gradle`; workflow: `.github/workflows/build-gmap.yml`

## Ograniczenia (potwierdzone — nie obiecywać inaczej)
- Tylko Bluetooth (USB na tym aucie niemożliwe).
- Limit prędkości: NIEMOŻLIWY (auto nie wystawia, Google Roads zablokowane). CDS ma `DRIVING.SPEEDACTUAL`/`SPEEDDISPLAYED`, ale `GPSEXTENDEDINFO` zwraca śmieci.
- Lane guidance (pasy): NIEMOŻLIWE (Google Directions nie zwraca; tylko Mapbox/premium Nav SDK).
- Numer zjazdu z ronda: tylko z tekstu `html_instructions` (polskie liczebniki), brak pola strukturalnego.
- `location.bearing` — użyteczne do „heading-up".
- **Semantyka kroków Google:** `html_instructions`/`maneuver` opisuje manewr na POCZĄTKU kroku. Jadąc krokiem `cur`, nadchodzący manewr to `krok[cur+1]`, wykonywany w `krok[cur].endLocation`.
- **Protokół RHMI przyjmuje TYLKO pełne bitmapy** (`rhmi_setData` na modelu obrazu) — delta/dirty-rects nie istnieją. `rhmi_setData` jest synchroniczne (blokuje do ACK z auta).
- **Łącze BT ma dwa reżimy** (zmierzone z `gmap_perf.csv`): ~50 KB/s (częsty, bandwidth-bound, czas ∝ bajtom) i ~115 KB/s (latency-bound ~386 ms/klatkę). Optymalizować pod zły dzień.
- **Labelki RHMI nie są stylowalne** (font/rozmiar/kolor = firmware auta). Piksele 1:1 tylko przez komponenty obrazu.
- **Natywne komponenty NAŁOŻONE na obraz nie renderują się** (z-order ID6); obok obrazu — działają.
- **GPS auta (CDS) ma stały błąd lateralny** — grot pozycji wymaga snapowania do polilinii trasy.
- Deskryptor RHMI (smartthings = onlineservices id5 v2) jest podpisany — nie można dodawać komponentów; stan mapy (`hmiState 19`) ma wolne: `image 134` (raImageModel 530) + `label 135/136/137` (raDataModel 527-529) + tytuł stanu (526).
- **Pozycje RHMI (property 20/21) są WZGLĘDEM PADDINGU** — komponenty ustawiają `-padding + pozycja_ekranowa` (jak upstream). Dowód: build 86 z pozycjami bez korekt przesunął całą kartę w prawo/dół o padding. (Wcześniejsza teoria „absolutne" z buildów 78–83 była BŁĘDNA — tam problemem była za mała stała linii i bug maski.) Realne wymiary auta logowane do `gmap_nav.log` przy połączeniu.
- **Wszystko rysowane na projekcji musi leżeć w REGIONIE PRZECHWYTYWANIA** (`findInnerRect` na surowych wymiarach displaya, NIE `appWidth` RHMI) — maska/overlay poza kadrem po prostu nie trafia do klatki (build 83: brak prawych rogów karty).

## Stan bieżący — commit 97e1b71 (wersja 1.4.3-83) — KARTA APLIKACJI (WARIANT 2)
Architektura po rozdzieleniu panel/mapa (zysk zmierzony: bajty/klatkę 33→17,6 KB [−45%], fps 1,44→2,22 przy tym samym łączu ~50 KB/s, spiki 3× rzadsze):
- **Mapa**: czysta bitmapa bez panelu, komponent obrazu zwężony o 223 px (`FullImageView` + `NativePanel.PANEL_WIDTH_PX`), JPEG adaptacyjny jak wcześniej.
- **Panel natywny (lewy pas)**: dystans do manewru w TYTULE stanu (mały setData ~1/s, deduplikowany); zielony blok + Przyjazd/Pozostało jako **PNG w naszym stylu** (`NativePanelRenderer` → `image 134`, wysyłany tylko przy zmianie treści, „Pozostało" ziarno 100 m; `POSITION_X=-paddingLeft` — bez tego PNG chował się pod obrazem mapy). **Przełącznik RUNTIME**: ustawienie `MAP_NATIVE_PANEL` („Panel natywny (mniej BT)" w opcjach mapy w aucie i w telefonie) — OFF przywraca panel w bitmapie; pełne przełączenie po ponownym wejściu w mapę.
- **Ikony manewrów jak znaki drogowe**: 19 vector drawables `ic_gmap_*` (katalog `ManeuverIcons`), pełne mapowanie manewrów Google + heurystyka zjazdu (keep/ramp + „zjazd" w instrukcji → piktogram zjazdu); rondo C-12 z numerem; nieznany manewr → prosto + log.
- **Znacznik pozycji**: wbudowana kropka Google (`isMyLocationEnabled=true`). Własny grot + snap do trasy WYCOFANE po dwóch iteracjach (odklejał się — błąd lateralny GPS auta zmienny, nie stały; nie wracać bez nowego pomysłu).
- **Timing**: manewr `cur+1`, dystans do `krok[cur].endLocation`; <15 m od końca kroku = manewr wykonany → przełączenie kroku; panel pokazuje się od razu po przeliczeniu trasy; reroute bez zmian (>70 m / 2 odczyty / 10 s).
- **Instrumentacja `PERF_LOG=true`** (`MapFramePerfLog`): agregaty 2 s do `Android/data/me.hufman.androidautoidrive/files/gmap_perf.csv` (bytes, compress ms, round-trip setData, fps); wyjmowanie przez MTP bez adb; analiza: `analyze.py` (u Pawła na pulpicie/scratchpad).
Do weryfikacji w jeździe: snap grota, dystans w tytule, prawy margines PNG (26 px), przełączanie kroku na węzłach.

## Kluczowe pliki (flavor gmap)
- `GMapsNavController.kt` — logika nawigacji: snap do kroku, przełączanie kroku <15 m, reroute, parsowanie ronda, `maneuverType` (+ heurystyka zjazdu), manewr `cur+1`.
- `GMapsController.kt` — kamera, grot (snap do trasy `snapToRouteForPuck`), `pushGuidance` (panel bitmapowy i/lub natywny, dedup PNG po kluczu treści).
- `GMapsProjection.kt` — projekcja mapy; przy `NativePanel.ENABLED` panel bitmapowy ukryty, padding symetryczny; grot `buildLocationPuck`.
- `NativePanelRenderer.kt` + `ManeuverIcons.kt` + `res/drawable/ic_gmap_*.xml` — PNG panelu i katalog ikon-znaków.
- `NativePanel.kt` (main) — flaga ENABLED, szerokość pasa, mostek na wątek car (dedup dystansu).
- `MapApp.kt` (main) — podpięcie natywnych komponentów stanu 19 (pozycje, sinki), `FullImageView.kt` (main) — zwężenie obrazu mapy.
- `MapFramePerfLog.kt` (main) — instrumentacja CSV; `FrameUpdater.kt` — pomiary wokół compress/setData.
- `gmaps_projection.xml`, `MapAppMode.kt`, `gmaps_style_slim.json` — bez większych zmian.

## Keystore (najważniejsza lekcja)
Stały debug keystore (SHA-1 `A1:DA:...`, storepass/keypass `android`, alias `androiddebugkey`, base64 w sekrecie `DEBUG_KEYSTORE_BASE64`). Jawny `signingConfig` w `build.gradle` → `../debug.keystore` (NIE domyślna ścieżka AGP `~/.android/` — na runnerze GitHub nie działa, build #8 podpisał złym kluczem). Workflow dekoduje sekret do `$GITHUB_WORKSPACE/debug.keystore`. Stały podpis = instalacja „Aktualizuj" bez odinstalowania + zgodność ze Spotify App Remote.

## Kolejka
1. Jazda weryfikacyjna commit da275f8 (1.4.3-86) — KARTA APLIKACJI po poprawkach kalibracyjnych: pozycje ABSOLUTNE (PNG (0,56), mapa (223,0)), linia `PANEL_TOP_PX=56` (kalibrować tę JEDNĄ stałą wg `gmap_nav.log` z realnymi wymiarami), maska+padding z regionu przechwytywania (prawe rogi 22 px już w kadrze). Pineska: TYLKO z trasą, ostatni wierzchołek polilinii (fallback na geokod usunięty). Diagnostyka: `gmap_nav.log` (wymiary RHMI, koniec↔geokod, przerysowania, reroute) + `CrashFileLog.note` w połykanych zgonach `CarThread`.
2. Fotoradary etap 1 (po weryfikacji karty): samo-aktualizująca baza OSM Overpass + GITD/dane.gov.pl (updater WorkManager co tydzień, plik lokalny, zero sieci w jeździe), żółta belka z ikoną/odliczaniem/limitem; potem etap 2: odcinkowy (średnia = dystans wzdłuż trasy / czas, bez odczytu prędkości).
3. Kosmetyka ikon-znaków ze zdjęć (kształty rysowane „na oko" — iterować jak C-12).
4. Ostrzejszy JPEG w ruchu (q40) — panel już nie cierpi na kompresji; kolejne −25% bajtów.
5. Rozstrzygnięcie reżimu łącza: pipelining tylko jeśli trafi się dzień latency-bound (~115 KB/s); reżimy zmienne w OBU autach (X1 raz 72, raz 45 KB/s).
6. Obserwacja: „świrowanie" Google Maps na telefonie przy działającej nawigacji (brak związku technicznego — zweryfikować, czy się powtarza), jednorazowy reroute bocznymi drogami.
7. Po zakończeniu strojenia: `PERF_LOG=false` (albo zostawić — koszt pomijalny).

## Ustalenia z jazd (tryby widoku)
- Obrót mapy (2D+obrót) ≈ zero kosztu w bajtach (18,6 vs 17,6 KB/klatkę) — preferowany tryb Pawła.
- 3D (tilt) zauważalnie tnie przy słabym łączu — zostaje jako opcja.
