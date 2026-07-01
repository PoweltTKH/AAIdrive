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

## Stan bieżący — build #9 (commit bca3c52) — DOBRY
Podpis APK zweryfikowany: SHA-1 `A1:DA:07:...` (zgodny ze Spotify). Spotify działa.
Działa: Google Maps + turn-by-turn na iDrive po BT (split/Widescreen OFF); panel lewy w stylu GMaps (nagłówek manewru, Przyjazd/ETA, Pozostało, bez prędkości, tło `#FF1B1B1D`); slim styl mapy; rondo z numerem zjazdu; reroute (>70 m / 2 odczyty, interwał 10 s, reaguje tylko na zjazd z trasy NIE na korki); timing pokazuje nadchodzący manewr (`cur+1`), dystans do `krok[cur].endLocation`, na końcu „◉ Cel podróży".

## Kluczowe pliki (flavor gmap)
- `GMapsNavController.kt` — logika nawigacji, dopasowanie do polilinii kroku, snap do przodu, reroute, parsowanie ronda, pokazywanie manewru `cur+1`.
- `GMapsProjection.kt` — panel lewy dopasowany do splitu, tło nieprzezroczyste, slim domyślny, prędkość usunięta.
- `gmaps_projection.xml` (layout) — układ panelu.
- `GMapsController.kt`, `FrameUpdater.kt`, `MapAppMode.kt`, `gmaps_style_slim.json` — wcześniejsze buildy, bez zmian.

## Keystore (najważniejsza lekcja)
Stały debug keystore (SHA-1 `A1:DA:...`, storepass/keypass `android`, alias `androiddebugkey`, base64 w sekrecie `DEBUG_KEYSTORE_BASE64`). Jawny `signingConfig` w `build.gradle` → `../debug.keystore` (NIE domyślna ścieżka AGP `~/.android/` — na runnerze GitHub nie działa, build #8 podpisał złym kluczem). Workflow dekoduje sekret do `$GITHUB_WORKSPACE/debug.keystore`. Stały podpis = instalacja „Aktualizuj" bez odinstalowania + zgodność ze Spotify App Remote.

## Kolejka
1. Nawigacja — test w ruchu (timing, rondo, reroute).
2. Build C — widok 3D / za samochodem: tilt + heading-up z `location.bearing`, jako przełączniki. Kompromis: więcej zmian klatki = gorsza kompresja po BT.
3. Drobne — `departure_time=now` w Directions (trasa świadoma ruchu + realniejsze ETA).
