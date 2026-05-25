# AnimePiP

An unofficial Android WebView wrapper for Crunchyroll, focused on reliable Picture-in-Picture support.

The app loads the Crunchyroll website inside an Android `WebView`, adds a native screen-relative PiP button, and uses Android's Picture-in-Picture APIs to keep video playing in a floating window.

> Disclaimer  
> AnimePiP is an unofficial personal project. It is not affiliated with, endorsed by, sponsored by, or associated with Crunchyroll, LLC or Sony Pictures Entertainment. Crunchyroll is a trademark of its respective owner.

## What it does

- Opens Crunchyroll in an Android WebView.
- Uses a desktop browser user agent so the web player is served instead of an app-only mobile page.
- Adds a native Android PiP button over the WebView.
- Enters Android Picture-in-Picture from the real web player fullscreen view.
- Adds PiP media controls:
  - play / pause
  - jump back 10 seconds
  - jump forward 10 seconds
- Keeps Android back navigation inside the app where possible.
- Applies PiP-specific layout fixes to reduce black bars and cropping.

## What it does not do

AnimePiP is not a downloader, DRM bypass, custom video player, or replacement Crunchyroll client.

It does not:

- download episodes
- extract stream URLs
- intercept license requests
- intercept video segments
- bypass Widevine or DRM
- reimplement the Crunchyroll player
- provide access to content without a valid Crunchyroll account

Playback, subtitles, quality selection, buffering, and DRM remain handled by Crunchyroll's own web player.

## Current status

This is experimental and very much a “make Android WebView do the least cursed thing possible” project.

Known rough edges:

- PiP behaviour depends on Android WebView, Android version, and Crunchyroll's current web player.
- Seeking from PiP can be unreliable when jumping outside currently buffered video chunks.
- Very small PiP windows may crop or render imperfectly on some devices.
- Crunchyroll can change its web player DOM or controls at any time, which may break injected control logic.

## Requirements

- Android Studio
- Android device or emulator with Picture-in-Picture support
- A Crunchyroll account
- Internet access

## Building

Clone the repository:

```bash
git clone https://github.com/gsprotts/AnimePiP.git
cd AnimePiP
```

Open the project in Android Studio, let Gradle sync, then run it on a device or emulator.

You can also build from the command line:

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK will be under:

```text
app/build/outputs/apk/debug/
```

## Release builds

Release builds are produced by GitHub Actions when pushing a tag matching `v*`.

Required repository secrets:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

## Usage

1. Install and open the app.
2. Sign in to Crunchyroll in the WebView.
3. Start an episode.
4. Tap the orange PiP button.
5. The app attempts to enter the web player's fullscreen mode, then Android PiP.
6. Use the PiP controls for play/pause and short skips.

## Privacy

See [`PRIVACY.md`](PRIVACY.md).

## License

This project is licensed under the MIT License.

You are free to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the software, provided that the copyright notice and license text are included.

See [`LICENSE`](LICENSE) for details.

## Contributing

This is a personal experimental project, but small fixes are welcome.

Useful areas for improvement:

- More robust PiP entry across Android versions.
- Safer DOM targeting for player controls.
- Device-specific notes for PiP resize/cropping quirks.

## Legal note

This project is intended for personal experimentation with Android WebView and Picture-in-Picture behaviour. Use it responsibly and respect the terms of the services you access through it.

## Security notes

`mixedContentMode` is set to `MIXED_CONTENT_NEVER_ALLOW` and `usesCleartextTraffic` is disabled.
This means the WebView will silently block any resource (images, subtitles, scripts) served over
plain HTTP rather than HTTPS. If content mysteriously stops loading after a Crunchyroll player
update, this is the first thing to check.
