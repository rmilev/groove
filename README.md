# Groove — live lyrics for Android TV

Groove listens to whatever is playing in the room (a record, a streaming app, anything) through the TV's built-in microphone. It shows the cover art and the lyrics in time with the music. It keeps listening the whole time it's on screen:
- It re-checks every 7 seconds, and every 4 seconds near the end of a song, so a skipped track, a seek or the next song shows within seconds.
- A short break in the sound, like a skip or the groove between tracks on a record, or a sudden jump in loudness, brings the next check forward.
- When the music stops and the room goes quiet, it goes back to listening within a few seconds.
- On a turntable running slightly fast or slow, it measures the speed and keeps the lyrics in step.

## Features

- **Recognition:** Shazam's song-matching service. The app makes the audio fingerprint itself with a Kotlin port of [SongRec](https://github.com/marin-m/SongRec)'s algorithm, the same approach the open-source [Audile](https://github.com/aleksey-saenko/MusicRecognizer) app uses. This isn't an official Shazam API, so it may stop working if Shazam changes it.
- **Lyrics:** [LRCLIB](https://lrclib.net), a free, open database of time-synced lyrics.
- **Full credits:** when Shazam names only the main artist, [MusicBrainz](https://musicbrainz.org) fills in the rest ("Vince Staples feat. Kendrick Lamar & KUČKA").
- **Artist photos:** Shazam's photo plus up to four widescreen photos from [TheAudioDB](https://www.theaudiodb.com), shown in a random order (new for every song) every 40 seconds.
- **Looks:** Stage (artist photo with lyrics beside it), Glass (a frosted panel) and Cinema (centred lyrics over a slowly drifting photo). Up and down switch while a song is showing; Groove remembers your choice.
- **Visuals:** when it hears music it doesn't know, after a few seconds a visual takes over the screen and the record moves to the corner: Aurora (ribbons of light), Horizon (a mirrored spectrum), Halo (a ring of light around a record) or Drift (flying through soft lights), all following the music through the microphone. Up and down pick one, or "Just the record"; Groove remembers your choice.
- **Remote:** OK re-checks right away. Left and right shift the lyrics 0.2 s later or earlier if they feel off; the app remembers the setting.

Only the fingerprint (a list of spectral peaks) leaves the TV, never the audio itself, and the microphone is only on while the app is on screen.

## Install on your TV

1. Download `groove-<version>.apk` from [Releases](https://github.com/rmilev/groove/releases).
2. Install it with the Downloader app or `adb install groove-<version>.apk`.
3. Open Groove and allow the microphone. The TV needs a built-in microphone (most recent Sony BRAVIA and Google TV sets have one).

## Build

You need JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # signed if keystore.properties exists, otherwise debug-signed
```

Release signing reads `keystore.properties` from the project root, which git ignores (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).

The emulator's microphone is silent, so for testing you can feed the app a file instead (16 kHz mono 16-bit PCM in the app's files directory):

```bash
adb shell am start -n mk.lyrics/.MainActivity --es testPcm <file>
```

## License

GPL-3.0 (see [LICENSE](LICENSE)), because the fingerprint code is derived from SongRec.
