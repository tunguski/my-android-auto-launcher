# Navi simple — user guide

Navi simple is a home screen for an Android head unit. It replaces whatever the
unit came with: a black screen with the time, the outside temperature, a warning
line when the road is about to get dangerous, and five large buttons that can be
hit without looking.

Everything else it does — the forecast, the trip recorder, the settings — sits one
tap away and always comes back to that black screen.

![The home screen as it looks while driving](previews/home.png)

This guide covers version 1.9. Every picture in it is a real screenshot of that
build on a 1280×720 head unit screen, taken automatically when the guide is
produced, so nothing here is a mock-up.

---

## What it is for

A head unit screen is a lamp pointed at the driver face. At night a bright
launcher is worse than no launcher at all, and a grid of twenty small icons is
unusable at 90 km/h. Navi simple answers both: the background is pure black and
never anything else, the text is grey rather than white, and nothing on the home
screen is smaller than a thumb.

The second thing it answers is that a cheap unit has no outside thermometer. This
one is reported over the car bus, and on the author unit the bus answers "no
protocol". So the temperature comes from the internet instead, from the position
the unit already knows, and with it come the warnings that matter on a winter
road: black ice above all.

## Requirements

| What | Needs |
| --- | --- |
| Android | 8.0 (API 26) or newer |
| Screen | any; the layout is built from the screen height, landscape |
| Permissions | location (for weather and trip recording), notifications |
| Internet | only for the forecast; everything else works offline |
| Accounts | none. No sign-in, no telemetry, no advertising |

The app has no dependencies of its own: no AndroidX, no Google Play services, no
third-party library. The APK is under 100 KB.

---

# The home screen

![The home screen, 100% size](previews/home_size_100.png)

The screen has two halves. The clock and the temperature share the upper half,
five tiles fill the lower one.

## The clock

The time follows the unit 12- or 24-hour setting. Under it is the date in the
language the app is set to.

- **Tap the clock** to dim the screen for night driving, and tap again to undo.
- **Hold the clock** to jump straight to the settings panel.

## The temperature and the warning line

The temperature is the outside temperature at the current position, refreshed
every three minutes from the Open-Meteo service. Tapping it opens the full
forecast.

When the weather needs attention, a warning triangle appears next to the
temperature and a line of warnings appears underneath, in a muted yellow that is
visible at night without dazzling.

![All five warnings at once](previews/home_warn_all.png)

## The tiles

| Tile | What it does |
| --- | --- |
| Navigation | opens the navigation app installed on the unit |
| Spotify | opens Spotify, or the Play Store page if it is not installed |
| Trip | opens the trip screen: time, distance, route |
| Weather | opens the forecast |
| All apps | lists every app on the unit, including the original home screens |

There is deliberately no settings tile. System settings are in the app list, and
the settings of the launcher itself are behind the **Screen layout** button there,
or behind a long press on the clock.

## Going back

Whatever is on screen, both **Back** and the **Home** key return to the black
screen. The launcher can never be closed, and Back on the home screen does
nothing at all — which is the point: nothing the driver presses can leave them
looking at a blank unit.

---

# The weather screen

![The forecast](previews/weather.png)

The screen shows, from the top:

- the current conditions: icon, temperature, description, place, what it feels
  like and the wind speed;
- the warning line, when there is one;
- the next twelve hours, each with its temperature and either the chance of
  precipitation or a one-word description;
- the next four days, each with its high and low.

![The forecast with a warning](previews/weather_warning.png)

## What raises a warning

A warning is raised for what is happening now or within the next three hours.

| Warning | Raised when |
| --- | --- |
| **Black ice** | temperature at or below +3 °C; or freezing rain or freezing drizzle; or precipitation while the temperature is at or below +3 °C |
| **Storm** | a thunderstorm (WMO code 95 or above) |
| **Strong wind** | gusts of 70 km/h or more, the Polish IMGW first-level alert threshold |
| **Dense fog** | fog reported, or visibility below 200 m within the coming hour |
| **Heavy snow** | heavy snowfall reported, or 1 cm an hour or more |

Black ice is the only one that also recolours the temperature itself, because it
is the one that changes how the car behaves rather than how far the driver can
see.

![Black ice](previews/home_warn_ice.png)

Freezing rain counts even when it is well above freezing right now: the road
surface is what matters, and it is colder than the air.

![Freezing rain forecast two hours ahead, at +6 °C](previews/home_warn_icerain.png)

![Storm](previews/home_warn_storm.png)

![Strong wind](previews/home_warn_wind.png)

![Dense fog](previews/home_warn_fog.png)

![Heavy snow, which also means black ice](previews/home_warn_snow.png)

## Where the data comes from

Open-Meteo (open-meteo.com), free, no key, no account, licensed CC BY 4.0. The
only thing sent is a latitude and a longitude rounded to three decimal places.
The last reading is cached, so the screen is never empty after a cold start.

---

# The trip screen

![A drive in progress: Warsaw to Gdansk](previews/trip.png)

The trip screen answers three questions and draws one picture.

| Figure | Meaning |
| --- | --- |
| **Time** | how long the current drive has been going |
| **Distance** | how far it has gone, along the recorded track |
| **To home** | straight-line distance to the configured home point |

The shape on the left is the route that has been driven. There is no map under it,
no street names, no scale: just the shape, which is enough to recognise a drive at
a glance and reveals nothing if somebody else looks at the screen. The yellow dot
is the latest position.

## Earlier trips

Along the bottom are the recorded trips, newest first, each with its distance and
duration. **now** is the drive in progress. Tapping any of them shows that trip
instead.

![An earlier trip: a short errand around town](previews/trip_earlier.png)

Before the first drive the screen says so rather than showing an empty box.

![Nothing recorded yet](previews/trip_empty.png)

## How recording works

- A position is recorded **every ten seconds** while the unit is on.
- A fix less accurate than 50 m is discarded as noise.
- Movement of less than 5 m is treated as standing still and is not stored, so a
  car parked for an hour does not fill the file with the same point.
- A jump of more than 2 km between two fixes is a GPS glitch: the point is kept,
  but the distance is not counted.
- **A pause longer than five minutes starts a new trip.** Stopping for fuel keeps
  the same trip; parking overnight starts a new one.
- If the unit restarts mid-drive, the trip is picked up again from disk instead of
  being split in two.

Recording runs in a foreground service, which is why a notification showing the
distance and duration sits in the status bar. Android gives a background app only
a handful of positions an hour, and the recording has to keep working while the
navigation app is in front, so the notification is not optional.

## Where the trips are kept

One CSV file per trip, in the private app folder on the unit storage:

```
Android/data/pl.matsuo.navi/files/trips/trip-20260115-071530.csv
```

Each line is `epochSeconds,latitude,longitude`. About 32 bytes per fix, so an hour
of driving costs roughly 11 KB and a year of ordinary use a few megabytes.
Nothing is uploaded anywhere. The files can be copied off over USB or with a file
manager, and they open in any spreadsheet.

---

# Settings

The settings panel is reached from **All apps → Screen layout**, or by holding the
clock on the home screen.

![Settings, upper half](previews/settings_top.png)

## Clock size

Four sizes, because head units differ and so do eyes. 100 % is the default; the
smaller variants shrink the clock sharply and the rest of the screen gently.

![100 % — the default](previews/home_size_100.png)

![80 %](previews/home_size_80.png)

![67 %](previews/home_size_67.png)

![50 % — for a small or oddly scaled screen](previews/home_size_50.png)

## Brightness

The slider runs from 15 % to 100 % and dims **only the text and the icons**. The
background stays pure black at every setting, which is the entire reason the app
exists.

## Night dimming

A second, deeper level of dimming, toggled by tapping the clock on the home
screen. The switch here says which way it currently is.

![Night dimming on](previews/home_night.png)

## Language

![Settings, lower half](previews/settings_bottom.png)

English, Polish, or whatever the unit itself is set to. The choice applies
immediately, including the date under the clock and the order the app list is
sorted in.

![The home screen in Polish](previews/home_polish.png)

## Home point

The point the **To home** figure measures from. It can be set two ways:

- **Set home here** stores the current position. Fastest, if the car is at home.
- **Enter an address…** looks the address up through the unit geocoder, which
  needs a working internet connection at that moment.

## Set as default home screen

This is the button that makes the unit come up in Navi simple after every ignition
instead of a stock Android screen. On Android 10 and newer it asks the system
directly; on older units, and whenever that fails, it opens the system home-screen
picker instead, which is also available as the second button.

> If the launcher is not default, the unit will show its own screen after the
> ignition is switched off and on again. This is the first thing to check if that
> happens.

---

# The app list

![All apps](previews/apps.png)

Every launchable app on the unit, sorted by name in the current language. The
original home screens the unit came with are listed first, labelled
**Original screen: …**, so going back to the stock interface is always one tap
away.

The **Screen layout** button in the corner opens the settings described above.

---

# Installing and updating

The APK is not on any store. Install it by hand:

1. Copy `navi-<version>-debug.apk` or `navi-<version>-release.apk` onto the unit —
   over USB, from a file manager, from a local web server, or via Bluetooth.
2. Allow installation from unknown sources when the unit asks.
3. Open the app once and grant location and notification permission.
4. Open **All apps → Screen layout** and press **Set as default home screen**.

Updating is installing the newer APK over the older one. Settings and recorded
trips are kept.

> The file name always carries the version, so two downloads can never be
> confused for one another.

---

# Privacy

- No account, no sign-in, no analytics, no advertising, no crash reporting.
- The only network request is the forecast, to open-meteo.com, carrying a position
  rounded to roughly a hundred metres and nothing else.
- Recorded trips never leave the unit.
- The app asks for location and notifications, and for nothing else.

---

# Troubleshooting

| Symptom | What to do |
| --- | --- |
| The stock screen comes up after the ignition | Settings → **Set as default home screen** |
| The temperature stays blank | Grant location permission; check the unit is online |
| The trip screen shows no route | Nothing recorded yet, or the GPS has no fix |
| Distance stays at zero while driving | The unit has no GPS fix, or location permission was refused |
| The recording notification is missing | Grant notification permission; Android hides it otherwise |
| The screen is too bright at night | Tap the clock, or lower the brightness slider |
| Text is too small or too large | Settings → Clock size |
| Spotify opens the Play Store | Spotify is not installed on the unit |

---

# Licence and source

Navi simple is MIT-licensed. The source, the build instructions and this guide are
in the project repository. Weather data is from Open-Meteo under CC BY 4.0.
