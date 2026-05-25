# Privacy

AnimePiP is a local Android WebView wrapper for Crunchyroll.

The app itself:

- does not operate a server
- does not collect analytics
- does not intentionally collect personal data
- does not download episodes
- does not extract stream URLs
- does not intercept license requests
- does not bypass DRM or Widevine
- does not reimplement Crunchyroll's player

Crunchyroll login, playback, cookies, cache, and session handling happen inside Android WebView and Crunchyroll's own website/player.

Android WebView and Crunchyroll may store cookies, cache, local storage, or session data locally on the device. Android app backup is disabled for this app to reduce the chance of session data being copied into cloud backup or device-transfer flows.
