# Loop

A local-first health companion for Android, built in Kotlin and Jetpack Compose. It reads your heart rate and
stored history from a WHOOP strap over Bluetooth, and turns it into recovery, sleep, strain, stress and more,
all calculated and stored on your phone. No account, no cloud, no ads, no analytics.

**Loop builds on [NOOP](https://github.com/ryanbr/noop) by [ryanbr](https://github.com/ryanbr).** It is a ground-up
Kotlin and Jetpack Compose rebuild of that project, and its recovery and strain methods, its WHOOP strap protocol work
and its WHOOP export import are adapted from NOOP's documented approach. Thank you to NOOP and its contributors.
See [NOTICE.md](NOTICE.md).

> **Loop gives wellness estimates, not medical advice.** It is not a medical device. It is independent and not
> affiliated with, or endorsed by, WHOOP, Inc.; the WHOOP name is used only to describe compatibility.

## What it does

- **Today**: your recovery, sleep and strain, a mood check-in, and what is behind today's recovery.
- **Overview**: how you are doing right now and over the last day, 7 days and 30 days, with a score, what is going
  well and what to work on. Every number shows whether it is good, and what a good range is.
- **Health log**: medicines (with before/after impact on your numbers), injuries and illness (with a recovery
  check), kept in one place.
- **Explore, Practice, You**: every metric by category, guided breathing, interval timer, strength log, journal,
  strap wake alarm, and your profile (with a recommended sleep need).
- **Live**: live heart rate and clearly labelled live estimates (HRV, breathing, stress), logged under daytime.

## Supported devices

Tested with a WHOOP 5.0. WHOOP 4.0 support exists but has not been tested on a real strap. Any Bluetooth heart-rate
device works for live heart rate. Downloading a strap's stored history is WHOOP-only and works by talking to the strap
the way the official app does, which is unofficial and could change with a firmware update.

## Build

Requires JDK 17 or newer and the Android SDK. Point `local.properties` at your SDK (`sdk.dir=...`), then:

```
./gradlew testDebugUnitTest assembleDebug
```

A signed release build reads its key from `~/.loop-signing/keystore.properties` (or the file named by the
`LOOP_SIGNING` environment variable); without it the release build is left unsigned. The key is never stored in
this repository.

## Privacy

Everything is calculated and stored on the phone. The app does not request the internet permission, so it cannot upload anything.
You can export all of your data as a spreadsheet or erase it at any time from the You tab.

## Credits

- **[NOOP](https://github.com/ryanbr/noop)** by ryanbr, the project Loop is rebuilt from. Required Notice: Copyright 2026 NoopApp.
- **Quicksand** typeface by The Quicksand Project Authors, under the SIL Open Font License 1.1.

## Licence

Licensed under the PolyForm Noncommercial License 1.0.0. Free for personal and other non-commercial use.
The Quicksand typeface is by The Quicksand Project Authors, used under the SIL Open Font License 1.1
(see `FONT_LICENSE_Quicksand_OFL.txt`).
