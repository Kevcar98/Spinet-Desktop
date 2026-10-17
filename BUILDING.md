# Building the Desktop App (personal notes)

All commands run from the repo root in PowerShell.

## One-time setup

1. **Full JDK 21.** Packaging needs `jpackage`, which Android Studio's bundled JBR doesn't ship.
   The build looks for it at:
   ```
   C:\Program Files\Java\jdk-21.0.11
   ```
   If yours lives elsewhere, set the env var before packaging:
   ```powershell
   $env:PACKAGING_JDK = "C:\path\to\jdk-21"
   ```

2. **Inno Setup** (installer builds only):
   ```powershell
   winget install JRSoftware.InnoSetup
   ```
   The build finds `ISCC.exe` automatically (winget per-user or Program Files install).
   For a custom location, set `$env:INNO_SETUP_ISCC` to the full ISCC.exe path.

## Run in dev (no packaging)

```powershell
./gradlew :composeApp:run
```

## Installer (.exe setup wizard)

```powershell
./gradlew :composeApp:packageInstaller
```

Output:
```
composeApp\build\compose\binaries\main\installer\Spinet-Setup-1.0.0.exe
```

- Update-aware: running it on a machine with an older version updates in place.
- Desktop-shortcut checkbox (on by default) + launch-after-install option.
- Uninstalls normally via Settings > Apps.
- Settings live in `%USERPROFILE%.spinet\settings.properties`.

## Portable version (USB stick)

```powershell
./gradlew :composeApp:packagePortable
```

Output:
```
composeApp\build\compose\binaries\main\portable\Spinet-Portable-1.0.0.zip
```

Zip layout (update-safe):
```
Spinet-Portable/
  portable.txt   <- marker; switches the app into portable mode. Don't delete.
  UPDATING.txt   <- update instructions for the end user
  data/          <- created on first run: settings, playlists, servers, themes
  app/           <- Spinet.exe + runtime  (replace this to update)
```

- Unzip anywhere (USB stick included), run `app/Spinet.exe`.
- All settings/library data live in `data/`, next to the marker, **not** inside
  `app/`. Nothing is written to the host machine; move the folder and everything
  moves with it.
- **Updating keeps everything**: the user replaces only the `app/` folder; the
  `data/` folder and `portable.txt` stay put. The app finds the marker by walking
  up from the exe, so `data/` one level above `app/` is picked up automatically.
  (The older flat layout, with the marker next to the exe, still works.)
- To "convert" an installed copy to portable behaviour: drop an empty
  `portable.txt` next to its exe (not recommended for the Program Files install, because
  the folder isn't writable without admin).

## Version bumps

Edit `appVersion` in `composeApp/build.gradle.kts` (currently `1.0.0`). Both the
installer filename and the portable zip name pick it up. Keep `msiUpgradeUuid`
unchanged forever, because it's how Windows knows a new installer is an upgrade.

## If a build fails

- `jpackage` errors: wrong or missing JDK; check `PACKAGING_JDK`.
- "Inno Setup not found": install it (see setup) or set `INNO_SETUP_ISCC`.
- Stale outputs: run `./gradlew clean`, then package again.
