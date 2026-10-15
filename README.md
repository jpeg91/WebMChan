# WebMChan

No fuss 4chan WebM converter for Android.

Pick a video, choose a board, get a `.webm` that fits.

Select from a list of boards and their requirement presets (or set your own).

Converted videos target the highest possible quality within the board's constraints.

Output is saved to `Download/WebM4chan/` and can be shared straight from the app.

Everything runs locally. The app has no network permission, nothing is uploaded.

> **Status:** early version. It works, but it's a debug-signed APK. Install as you would any `.apk`.

## What it does

- **Board targets.** Pick the board(s) you're posting to and the app applies that board's WebM rules: file size, maximum length, minimum resolution and whether audio is allowed. Boards with identical rules are grouped, and each option shows its limits and the boards it covers (e.g. `4 MB · Min 500×500 · 120 s` for `/hc/ /s/`). There's also a Custom option with your own size limit.
- **Quality or Speed.** Quality is the default and gives the best detail. Speed encodes noticeably faster at a small cost in very fine detail. Both stay just under the size limit.
- **Maximum quality inside the limit.** Output is VP8 (libvpx), 2-pass, with the bitrate calculated from the size limit and the clip length. The app aims for about 95% of the limit, checks the real file size, and retries with a smaller budget (up to 4 attempts) if the result came out over. It very rarely misses on the first try.
- **Resolution and frame rate.** It picks the largest resolution (up to 1080p) that still gets a reasonable bitrate and caps the frame rate at 30 fps.
- **Minimum resolution.** If your video is below a board's minimum, it's scaled up to meet it, keeping the aspect ratio, and still encoded to the file size limit.
- **Audio.** "Remove audio" gives all the space to video quality. When audio is kept it's Vorbis at 64 kbps. Boards that don't allow audio lock the switch to "remove".
- **Length limit.** Clips longer than the board's limit are cut to it (splitting is planned).
- **Live log.** A scrolling readout of what ffmpeg is doing, with a **Copy log** button. It's handy for checking progress and for reporting errors.
- **Progress.** The first pass only analyses the video and ffmpeg reports no progress during it, so the bar animates until the second pass starts (Update: currently broken, but if it says 'analysing' it's working), then shows real progress during the second pass.
- **Output.** Files are saved to `Download/WebM4chan/` and can be shared straight from the app.

## Board targets

Taken from 4chan's board list (`boards.json`), 77 boards in 8 groups:

| Size | Min resolution | Max length | Audio | Boards |
|---|---|---|---|---|
| 4 MB | none | 120 s | no | 66 boards, from `/3/` to `/y/` |
| 2 MB | none | 120 s | no | `/b/` `/bant/` |
| 4 MB | 480×600 | 120 s | no | `/w/` `/wg/` |
| 4 MB | 500×500 | 120 s | no | `/hc/` `/s/` |
| 4 MB | 1000×1000 | 120 s | no | `/hr/` |
| 4 MB | none | 120 s | yes | `/r/` `/wsr/` |
| 4 MB | none | 300 s | yes | `/gif/` |
| 6 MB | none | 400 s | yes | `/wsg/` |

The rules are a snapshot from October 2026 and are built into the app (the `TARGETS` list in `Converter.kt`). If 4chan changes a board's limits, update that list, or use Custom in the meantime. Please report any found changes to 4chan's board limits in the 'Issues' tab here.

## Build

1. Open this folder in Android Studio (Ladybug or newer). It will create the Gradle wrapper and sync.
2. Run on a physical arm64 device (Android 10+). Emulators won't work, because the FFmpeg fork only publishes arm64-v8a binaries.
3. Build > Build APK(s) if you want to sideload it.

**FFmpeg dependency.** `app/build.gradle.kts` uses `dev.ffmpegkit-maintained:ffmpeg:7.1.6`, a community continuation of the retired FFmpegKit. If Gradle can't resolve it, check https://github.com/ffmpegkit-maintained/ffmpeg for the current coordinate. The app needs libvpx and libvorbis in whichever build you pick.

## Known limits

- Keep the app open while converting (the screen stays on). There's no background service yet.
- ARM-based 2-pass VP8 is slow: expect several minutes for a long clip, even more at 1080p. Speed mode helps.
- Boards with a high minimum resolution and a small size limit (e.g. `/hr/` at 1000×1000 in 4 MB) leave very little bitrate per pixel, so expect low quality there. That's a limit of the board's rules, not a bug.
- Videos filmed in portrait with rotation metadata may pick a slightly conservative resolution.
- Board rules can change on 4chan's side. See "Board targets" above.

## Features still to add

- VP9
- Batch mode
- Background service
- Split videos over the target time length
- UI improvements
- Frame rate options
- Smarter progress (pass 1 can currently only show a currently broken animated bar)
- Custom resolution targets that maintain aspect ratio
- Custom output time length and custom splitting options
- Bug reporting (the Copy log button is a start). Please report any issues through github
- Update handling
- Naming and save folder conventions and settings
- About / app info page
- App name standardising
- Publish APK
