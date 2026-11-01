# Rain Check

A small standalone Wear OS app for the Pixel Watch 2 that answers one question: **is it about to
rain where I am (or where I'm going), so can I walk the dog now?**

- **Tile:** the verdict (e.g. `Dry for ~45 min`), a 12-bar rain chart for the next 3 hours, which
  location it's for, and when it last updated. Tap the tile to refresh; tap the location chip to
  change location.
- **Complication:** a short verdict for your watch face (`Dry 45m`, `Dry 3h+`, `Rain`, `—`).
  The long version includes the place: `Home: Dry for ~45 min`.
- **App:** the same verdict, a Refresh button, and Location settings (current location or up to
  5 saved places).

Forecast data is from [Open-Meteo](https://open-meteo.com/) (free, no key). Address search uses
[Nominatim / OpenStreetMap](https://nominatim.org/).

> **Known limitation:** Open-Meteo has no native 15-minute model for Australia, so the
> 15-minute values are smoothed from hourly models. It's a forecast, not radar. The fetch sits
> behind a `RainSource` interface so a radar-based source can replace it later.

### Verdict rules

A 15-minute slot is **wet** if precipitation is at least **0.1 mm**. The countdown is worked out
from the cached forecast and the current time whenever something is drawn, rounded to the
nearest 15 minutes (minimum 15).

| Situation | Tile / app | Complication |
|---|---|---|
| Dry now, first wet slot in N min | `Dry for ~N min` | `Dry Nm` |
| Dry for the next 3 h | `Dry for 3h+` | `Dry 3h+` |
| Wet now, first dry slot in N min | `Raining · clears in ~N min` | `Rain` |
| Wet for the next 3 h | `Raining for 3h+` | `Rain` |
| Data older than 60 min, or none | `No recent data` | `—` |

If you switch location, the tile shows `Updating…` until the new place's forecast arrives. It
never shows one place's forecast under another place's name.

---

## 1. Optional: a "Home" place baked in at build time

You can skip this and add places on the watch instead (**Location → Save where I am now** or
**Search**).

If you do set `HOME_LAT` and `HOME_LON`, the app creates a saved place called **Home** on first
run and makes it active.

**GitHub Actions:** in the repo go to **Settings → Secrets and variables → Actions → New
repository secret**, and add:

| Name | Example |
|---|---|
| `HOME_LAT` | `-27.4698` |
| `HOME_LON` | `153.0251` |

Then re-run the workflow (**Actions → build → Run workflow**).

**Local builds:** put them in `local.properties` in the project root. That file is gitignored, so
the coordinates are never committed:

```properties
HOME_LAT=-27.4698
HOME_LON=153.0251
```

If neither is set, the app starts with no places and uses **Current location** once you grant
location permission. With no permission and no places it falls back to Brisbane CBD, labelled
`Brisbane (default)`, and the tile asks you to set a location.

The seed only applies to a fresh install. Changing the secrets later doesn't move an existing Home.

## 2. Download the APK from GitHub Actions

1. Open the repo on GitHub → **Actions** → the latest green **build** run.
2. Under **Artifacts**, download **raincheck-debug-apk**. It's a zip.
3. Unzip it. Inside is `app-debug.apk`.

Each build is signed with the same committed debug key (`app/debug.keystore`), so a newer APK
installs over an older one and keeps your saved places.

## 3. Turn on developer options and wireless debugging on the Pixel Watch 2

1. On the watch: **Settings → System → About → Versions** (on some versions it's directly under
   **About**). Tap **Build number** 7 times until it says you're a developer.
2. Go back to **Settings → Developer options**.
3. Turn on **ADB debugging**.
4. Turn on **Wireless debugging**. The watch and your computer must be on the same Wi-Fi network.

**Put the watch on its charger while you do this.** Wireless debugging uses a lot of battery.

## 4. Pair, connect and install with adb

You need `adb` from the Android SDK platform-tools on your computer.

1. On the watch, in **Developer options → Wireless debugging**, tap **Pair new device**. It
   shows a pairing code and an `IP:port` for pairing.
2. On your computer:

   ```sh
   adb pair <WATCH_IP>:<PAIRING_PORT>
   # enter the 6-digit pairing code when asked
   ```

3. Back on the main **Wireless debugging** screen the watch shows a different `IP:port`. That's
   the connection port:

   ```sh
   adb connect <WATCH_IP>:<CONNECT_PORT>
   adb devices          # the watch should be listed as "device"
   ```

4. Install:

   ```sh
   adb -s <WATCH_IP>:<CONNECT_PORT> install -r app-debug.apk
   ```

   `-r` replaces an existing install and keeps its data.

5. When you're done, turn **Wireless debugging** off on the watch to save battery.

## 5. Location permission

Open **Rain Check** on the watch. Then:

- **Using saved places only** (Home, Work…): no location permission needed.
- **Current location** or **Save where I am now**: the app asks for location. Choose **While
  using the app** (it only ever asks for approximate location; it never asks for precise
  location, which adds nothing on a ~15 km weather grid).
- **Current location** in the background: after that, the app explains why it wants
  background access and asks again. Choose **Allow all the time**. On Wear OS this may take you
  to the system settings page; if so, pick **Location → Allow all the time**. If you skip it,
  **Allow all the time** stays available in Location settings.

If you don't allow "all the time":

- the location updates whenever you open the app;
- the rest of the time it uses the watch's last known location;
- the tile says `Current location (may be stale)`.

If location is unavailable entirely, the app keeps using the last coordinates that worked and
labels them `Last known location`.

## 6. Add the tile and the complication

**Tile:** from the watch face, swipe left to the end of your tiles → **+ Add tile** → **Rain
Check**. (Or long-press a tile to rearrange.) The first forecast appears within a few seconds if
you have a connection.

**Complication:** long-press the watch face → **Customise** (or the pencil) → swipe to the
complications screen → tap a slot → **Rain Check**. Small slots show the short text; wide slots
show the long text with the place name. Tapping it opens the app.

## 7. Troubleshooting

**The tile isn't updating**

- It fetches every 15 minutes when there's a network (that's Android's minimum for background
  work). The countdown in between is computed from the cached forecast, so `~45 min` ticks down
  without a fetch.
- Tap the tile body to fetch now.
- Battery Saver or Bedtime mode can delay background work. So can a watch that's been off its
  phone and Wi-Fi.
- Removing and re-adding the tile forces a redraw.

**"No recent data"**

- The last successful fetch is over 60 minutes old, or there hasn't been one yet.
- Check the watch has internet (Wi-Fi, or Bluetooth through the phone with the phone online).
- Open the app and tap **Refresh**. If it still says this, Open-Meteo may be unreachable; it
  keeps trying every 15 minutes, and the last good data stays on screen until it's an hour old.

**Current location looks stale**

- The tile says `Current location (may be stale)` when background location isn't set to "Allow
  all the time". Fix it in **Location → Allow all the time**, or on the watch: **Settings → Apps
  → Rain Check → Permissions → Location → Allow all the time**.
- Make sure location is on: **Settings → Location**.
- Opening the app always refreshes the location.

**Wireless debugging is draining the battery**

- It does. Keep the watch on the charger while installing and turn **Wireless debugging** off
  afterwards. You can leave **ADB debugging** on; it costs nothing without a connection.

**Install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`**

- You have a build signed with a different key installed (for example one built in Android
  Studio with its own debug key). Uninstall first: `adb uninstall dev.nick.raincheck`. This
  clears saved places.

---

## Development

- Kotlin, Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`).
- `core/`: pure Kotlin, no Android. Verdict logic (`Verdict.kt`), location resolution
  (`LocationResolver.kt`), what to draw (`Presentation.kt`), the Open-Meteo `RainSource` and
  the Nominatim client. All unit-tested with JUnit.
- `app/`: the Wear OS app. Tile (`tile/`), complication (`complication/`), Compose UI (`ui/`),
  WorkManager fetch (`work/Refresh.kt`), DataStore storage (`data/`).
- A rain-alert buzz can be added later as another `ForecastObserver` in `work/Refresh.kt`: it
  gets the previous and new forecast after every fetch.

```sh
./gradlew test assembleDebug
```

### Permissions

The app requests `ACCESS_COARSE_LOCATION` and `ACCESS_BACKGROUND_LOCATION` at runtime, and
declares `INTERNET` and `ACCESS_NETWORK_STATE`. Libraries add a few install-time permissions
to the merged manifest that you're never prompted for (WorkManager's `WAKE_LOCK`,
`RECEIVE_BOOT_COMPLETED` and related ones it needs to keep the 15-minute job alive across
reboots).
