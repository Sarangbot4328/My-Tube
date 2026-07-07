# My Tube APK

Personal experimental Android version of My Tube.

## Current scope

- Native Android app shell
- Bottom tabs: YouTube, Home, Videos, Shorts, Channels
- Search bar
- Unofficial YouTube metadata extraction through NewPipeExtractor
- Streaming playback through AndroidX Media3 ExoPlayer
- No download feature yet

## Build on GitHub

1. Push this repository to GitHub.
2. Open the Actions tab.
3. Run `Build APK`.
4. Download the `my-tube-debug-apk` artifact.

The workflow builds a debug APK from `My Tube APK/app`.

## Notes

This is a personal experimental client. It uses NewPipeExtractor instead of the official YouTube API/player, so playback can break whenever YouTube changes its site or stream signatures.

NewPipeExtractor is GPL-3.0 licensed. Keep that in mind if this ever moves beyond private experimentation.
