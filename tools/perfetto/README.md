# Komikku startup trace

This captures a 12-second cold-start Perfetto trace for `app.komikkurnz.beta`.
The preview APK contains `KMK:` trace sections for the startup phases that are
otherwise indistinguishable in logcat.

## Record from Termux

From the repository root:

```bash
ADB_BIN=termux-adb tools/perfetto/record-komikku-startup.sh
```

The script force-stops Komikku, starts Perfetto, launches `MainActivity`, and
pulls both:

```text
komikku-startup-<timestamp>.pftrace
komikku-startup-<timestamp>.pftrace.timing.txt
```

Do not open other apps, change permissions, restore a backup, or start a library
update while the trace is recording. Keep the device below 37–38°C and use the
same app state for comparisons.

Open the `.pftrace` file locally at <https://ui.perfetto.dev>. Search for `KMK:`
in the Komikku process. Expected sections include:

```text
KMK:App.onCreate
KMK:App.patchInjekt
KMK:App.telemetry
KMK:App.platformSetup
KMK:App.importModules
KMK:App.logging
KMK:App.notificationChannels
KMK:App.hardwareBitmap
KMK:App.theme
KMK:App.coverMetadataLoad
KMK:App.widgetManager
KMK:App.workAndSync
KMK:App.migrator
KMK:App.newImageLoader
KMK:MainActivity.installSplash
KMK:MainActivity.superOnCreate
KMK:MainActivity.awaitMigration
KMK:MainActivity.firstComposition
KMK:HomeScreen.firstComposition
KMK:LibraryTab.firstComposition
KMK:Extensions.loadInstalled
```

`MainActivity` also reports fully drawn after the active startup tab finishes
loading. This allows Macrobenchmark and Perfetto to distinguish time to initial
display from time to full display.

Preview and benchmark builds include Compose Runtime Tracing, so the trace also
shows individual composable functions on Android 11/API 30 and newer. This
dependency is not included in release or FOSS builds.
