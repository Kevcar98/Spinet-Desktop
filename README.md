# Spinet for PC

Spinet is a music player for your own music. It plays the files on your
computer, the library on your own server, and online sources you add yourself.
There are no ads, no accounts and nothing to pay for. The installers are for
Windows; on macOS and Linux it runs from source (see [Run it](#run-it)).

It is written in Kotlin Multiplatform and Compose Multiplatform, and shares its
screens and logic with the Android app.

| Repo | What it is |
|---|---|
| Spinet-Desktop (this one) | Spinet for Windows (macOS and Linux from source) |
| [Spinet-Mobile](https://github.com/Kevcar98/Spinet-Mobile) | Spinet for Android |
| [Spinet-Server](https://github.com/Kevcar98/Spinet-Server) | The optional server you host yourself, which keeps your devices in sync |

## What it does

Spinet plays music from three places and shows them as one library:

1. Music on this computer. Pick a folder and play it offline.
2. Your own server. A library you host yourself, which you can upload to,
   browse and download from on every device you use.
3. Online sources. These come from extensions you install.

When you play a song, Spinet uses the copy closest to you: this computer first,
then your server, then online. Saving a song works the same way, so it is taken
from your server if it is already there. A song you already have on this
computer or your server is skipped instead of being saved twice.

Playlists, likes and play counts are kept on your server, so the PC and the
phone show the same thing.

## Features

- Search every source you have switched on at once, in one list.
- Filter any list by title or artist, and search your whole server library
  instead of one folder at a time.
- A full-window player, a small player that can stay on top of other windows,
  Windows taskbar buttons and media keys.
- Play a folder of your own music offline.
- Browse your server's folders as playlists, and upload, download or delete
  songs on it.
- Saved songs keep their title, artist and cover, so they show up properly in
  other players too.
- Covers and song details come from iTunes. Karaoke and tribute versions are
  ignored when looking for a match, and you can look a song up again if the
  first match was wrong.
- Playlists: create them, import them from a CSV file, drag songs to reorder
  them and pick a different version of a song.
- Shuffle, repeat all, repeat one, and a marker on the song that is playing.
- Built-in themes, and your own themes from hex colours.
- Updates from inside the app. It checks the GitHub releases, checks the
  download against its published SHA-256, installs it and restarts.
- Picks up where you left off: the queue, the song, the position, shuffle and
  repeat are all kept when you close the app.

## What is shared with the Android app

The screens, the view model, networking, the library and settings are all in
`commonMain`, which is the same code the Android app runs. Only the parts that
talk to the operating system are different.

| Part | Android | PC (`desktopMain`) |
|---|---|---|
| Playing audio | Media3 / ExoPlayer | JavaFX MediaPlayer |
| Saving songs with tags | MediaStore / SAF | `DesktopFileDownloader`, into a folder you choose |
| Local music folder | SAF picker | Swing folder picker and jaudiotagger |
| Importing and exporting files | SAF | Swing `JFileChooser` |
| Installing updates | Package installer | The MSI through `msiexec`, or swapping the portable folder |

## Requirements

- JDK 17 or newer (21 recommended; JavaFX 21 needs 17 or newer)
- No Android SDK needed

## Run it

```bash
./gradlew :composeApp:run
```

The first run downloads the dependencies, including the JavaFX jar for your
system, which `build.gradle.kts` picks for you.

## Build the installers

```bash
./gradlew prepareRelease
```

This builds the MSI and the portable zip, and writes `SHA256SUMS` next to them in
`composeApp/build/release/`. Those are the files to upload to a GitHub release,
which is where the in-app updater looks.

To build one at a time: `packageMsiWithPrompt` for the installer,
`packagePortable` for a folder that runs from anywhere and keeps its settings in
`./data`, or `packageDistributionForCurrentOS` for the standard Compose package.

## Audio formats

JavaFX plays mp3, aac/m4a, wav and aiff, which covers the songs Spinet saves.
FLAC, Opus and Ogg files on a server will be listed but won't play on the PC.

## Connect it to your server

1. Set up the server by following [Spinet-Server](https://github.com/Kevcar98/Spinet-Server).
   It runs with Docker Compose, and the guide covers hosting it for free on
   Oracle Cloud.
2. In the app, open Settings > My Servers and press Add a server. Enter your server's address, choose
   HTTPS or HTTP (HTTP also needs a port), add your username and password if
   you turned sign-in on, and press Save.
3. To add online sources, open Settings > Sources and install an extension.

## Reporting a problem

Open an issue with the **Bug report** form on the
[Issues](https://github.com/Kevcar98/Spinet-Desktop/issues) page.

Spinet writes a log every time it runs, and attaching it makes problems much
quicker to fix. Reproduce the problem, then open Settings > Updates and help >
Open log folder and attach `spinet.log`. It is here:

- Installed: `C:\Users\<you>\.spinet\spinet.log`
- Portable: `data\spinet.log` in your Spinet folder

Each start of Spinet replaces the log, so send it before opening Spinet again.
Server addresses, links, IP addresses and your user name are left out of it automatically.

## Project layout

```
composeApp/
  src/commonMain/   screens, view model, models, networking, settings, library
                    (the same code the Android app runs)
  src/desktopMain/  the PC entry point and the PC-only parts (audio, saving,
                    file pickers, small player, taskbar buttons, updater)
```

## License

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

Spinet is Free Software. You can use, study, share and change it under the
terms of the [GNU General Public License](https://www.gnu.org/licenses/gpl-3.0.html),
version 3 or (at your option) any later version. The full text is in
[LICENSE](LICENSE).

Its libraries are under licences that work with GPLv3: Compose, Ktor, kotlinx,
Coil and OkHttp (Apache-2.0 or MIT), JavaFX (GPLv2 with the Classpath
Exception) and jaudiotagger (LGPL).
