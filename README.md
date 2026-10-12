# WebMChan

Offline Android app: pick a video, choose a size limit, get a `.webm` that fits.
Output is saved to `Downloads/WebM4chan/` and can be shared straight from the app.

## What it does

- Video: VP8 (libvpx), 2-pass, bitrate computed from the size limit and clip length
- Audio: optional. Off by default ("Remove audio"), which gives all the space to video.
  When on: Vorbis at 64 kbps. 4chan only accepts audio on /wsg/ and /gif/.
- Picks the largest resolution (up to 1080p) that still gets a reasonable bitrate,
  and caps frame rate at 30 fps
- Targets ~95% of the limit, then checks the real file size and retries with a
  smaller budget (up to 4 attempts) if it came out over
- Clips longer than 300 s are cut to the first 300 s
- Limit presets: 4 MB, 6 MB, or a custom MB value

## Build

1. Open this folder in Android Studio (Ladybug or newer). It will create the Gradle wrapper and sync.
2. Run on a physical arm64 phone (Android 10+). Emulators are x86_64 and won't work,
   because the FFmpeg fork only publishes arm64-v8a binaries.
3. Build > Build APK(s) if you want to sideload it.

## Things to verify

- **FFmpeg dependency.** `app/build.gradle.kts` uses `dev.ffmpegkit-maintained:ffmpeg:7.1.6`,
  a community continuation of the retired FFmpegKit. If Gradle can't resolve it,
  check https://github.com/ffmpegkit-maintained/ffmpeg for the current coordinate.
  The app needs libvpx and libvorbis in whichever build you pick.

## Known limits

- Keep the app open while converting (screen stays on). There's no background service.
- Phone-based VP8 2-pass is slow: expect a few minutes for a long 1080p clip.
- Videos filmed in portrait with rotation metadata may pick a slightly conservative resolution.
