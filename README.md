# WebMChan

No fuss 4chan WebM converter for Android.

Pick a video (or a whole batch), choose a board, get `.webm` files that fit.

Select from a list of boards and their requirement presets (or set your own).

Converted videos target the highest possible quality within the board's constraints.

Output is saved to `Download/WebMChan/` by default, or to a `WebMChan` folder inside any folder you choose (including one on an SD card), and can be shared straight from the app.

Everything runs locally. The app has no network permission, nothing is uploaded.

> **Status:** Early development version. Uninstall any previously installed versions, then install as you would any `.apk`. "Unknown Sources" must be enabled in Developer Options. Please report bugs.

**[Download the Latest Release](https://github.com/jpeg91/WebMChan/releases/latest)**

## What it does

- **Board presets.** Pick the board(s) you're posting to and the app applies that board's WebM rules for you: file size, length, minimum resolution and audio. Or choose Custom and set your own size limit.
- **Batch mode.** Select one video or several, and convert them all in one go with the same settings.
- **Best quality that fits.** Each video is done in two passes (analysis then encode), aiming for about 95% of the size limit, so you get as much quality as the limit allows.
- **Simple options.** Choose Quality, Speed or Fastest, VP8 (recommended) or VP9, and whether to keep the audio.
- **Your choice of save location.** Videos are saved to `Download/WebMChan/` by default, or to a folder you choose, such as one on an SD card. Share them straight from the app.
- **Progress and log.** See what's happening while it converts, and copy the log if you need to report a problem.

<details>
  <summary><b>Screenshot (Outdated)</b></summary>

<img width="1080" height="5113" alt="WebMChan_Screenshot" src="https://github.com/user-attachments/assets/e90a05ba-a7e1-4d9e-9a5c-c66c57aa7559" />

</details>

<details>
  <summary><b>How it works</b></summary>

The app is a front end for FFmpeg (the community `ffmpegkit-maintained` build). Nothing leaves the device.

### Batch queue

Use **Choose videos** to select one video or several, and **Add videos** to keep adding more (a video that's already in the list is ignored). They queue up in the order you picked them. Before you start, you can reorder them (▲ ▼) or remove them (✕), and each shows its details and any warnings, such as being cut to the board's length limit or scaled up to its minimum resolution.

Every video in the queue is converted with the same settings, one after another. If a video fails, it's marked as failed and the rest carry on. **Skip current** drops the video being converted and moves to the next, and **Cancel all** stops the whole batch. Each finished video has its own **Share** button, and **Share all** appears when the queue has finished encoding.

Finished videos stay in the list. Pressing Convert again only processes the ones that haven't finished, which also lets you retry failures. To convert a finished video again with different settings, remove it and add it again.

### 1. Reading the video

When you pick videos, the app asks FFmpeg's probe tool for each one's length, width, height, frame rate and whether it has an audio track. That's all it needs to plan the conversion. When you tap Convert, the videos are processed one at a time: each is copied into the app's private cache so FFmpeg can read it as an ordinary file, and that copy is deleted afterwards.

### 2. Applying the board's rules

The board you pick supplies four numbers: the file size limit, the maximum length, the minimum resolution and whether audio is allowed. If the clip is longer than the maximum length, only the first part is encoded (splitting into parts is planned). Boards with identical rules are grouped into one option, and each option lists its limits and the boards it covers, for example `4 MB · Min 500×500 · 120 s` for `/hc/ /s/`. Custom has its own size limit, no minimum resolution and a 300 s length cap.

### 3. Working out the bitrate

A WebM's size is roughly its bitrate multiplied by its length, so the app works backwards from the limit:

1. The target size is **95% of the limit.** The small gap covers file overhead and the encoder's tendency to overshoot slightly.
2. That size, divided by the clip length, gives the total bitrate available.
3. If audio is being kept, 64 kbps is taken off for it. The rest is the **video bitrate.**
4. If the video bitrate would be under 40 kbps, the app stops and tells you the clip is too long for this limit. Removing audio gives the video more room.

Removing audio improves quality: all of that bitrate goes to the picture.

### 4. Choosing the frame size

More pixels need more bitrate to look good. The app measures this as **bits per pixel**: the video bitrate divided by the number of pixels per second. It tries sizes from large to small, using the source's own height plus 1080, 720, 480, 360 and 240, and takes the first one that gets at least 0.06 bits per pixel (0.12 in Fastest mode, which is why Fastest ends up with smaller frames). It never goes above 1080p, never above the source's own size, and never below the board's minimum.

If the source is below the board's minimum, it's scaled **up**. The scaling keeps the original aspect ratio and uses even width and height, as the encoder requires. Frame rates above about 30 fps are reduced to 30.

### 5. Encoding in two passes

The video is encoded twice:

- **Pass 1** is analysis only. The encoder watches the whole clip and records where the hard-to-compress scenes are. No file is produced, and FFmpeg reports no progress during this pass.
- **Pass 2** is the real encode. Using what pass 1 learned, it spreads the bitrate across the clip, spending more on busy scenes and less on simple ones. The result is more consistent quality and a file much closer to the target size than a single pass would give.

The encoder settings depend on what you chose:

| Setting | Encoder effort | Look-ahead | Frame size |
|---|---|---|---|
| Quality | `cpu-used 2` | 25 frames | normal |
| Speed | `cpu-used 4` | 25 frames | normal |
| Fastest | `cpu-used 5` | 8 frames | smaller |

"Encoder effort" is how hard the encoder searches for efficient ways to store the picture. It has nothing to do with CPU cores. Higher is faster but a little less efficient, so quality drops slightly at the same bitrate.

In plain terms: **Quality** is the default and gives the best detail. **Speed** encodes noticeably faster at a small cost in very fine detail. **Fastest** is faster again, but it uses a lower encoder effort and picks smaller frame sizes, so it looks a lot softer than Quality or Speed. Use it sparingly. All three stay just under the size limit.

When VP9 is selected, a few extra settings apply:
- **Multithreading.** Row-based multithreading is turned on, and Fastest adds tile columns so more cores can work at once.
- **Plain 8-bit colour.** The video is forced to standard 8-bit colour so that most players and can decode it.
- **Colour-space flag.** <s>The colour-space flag is set to "unspecified". Some downloaded clips are tagged as RGB, and VP9 would copy that tag into a format where it's invalid, which made players show green garbage. This fixes it in most cases. VP8 has neither problem, because it always stores standard 8-bit colour.</s>
Turns out that didn't fix it in most cases. Added -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv to the common args used by every encode (VP8 and VP9 alike), explicitly tagging the output as standard SDR video. Removed the old VP9-only -colorspace unknown workaround, since the new explicit bt709 tag supersedes it and is more correct than “unknown” (a real tag, not just “don’t tag it”). Left -pix_fmt yuv420p and -row-mt 1 as VP9-only, since those address the separate 10-bit/HDR and multithreading issues and don’t apply to VP8. 



Metadata (titles, GPS and so on) and subtitles are stripped from the output.

### 6. Audio

If audio is kept, it's encoded as Vorbis at 64 kbps stereo during pass 2. Otherwise the output has no audio stream at all. Removing audio gives all the space to video quality. Boards that don't allow audio lock the switch to "remove".

### 7. Checking the size and retrying

After pass 2, the app measures the real file. If it's within the limit, it's done. If it's over, the app shrinks the budget in proportion (aiming a little below the limit) and runs both passes again, up to 4 attempts in total. In practice the first attempt nearly always fits. If it still doesn't fit after 4 tries, the app says so.

### 8. Saving

Each finished file is saved using the original file name plus a short marker for the audio and speed settings used (for example `-MQ` for no audio at Quality, or `-AF` for audio kept at Fastest), and the **Share** button opens Android's share sheet.

By default the file goes to internal storage at `Download/WebMChan/`. **Choose folder** lets you pick somewhere else, such as a folder on an SD card, and **Use default** switches back. If you've chosen a folder, it's written through Android's Storage Access Framework into a `WebMChan` subfolder there (or straight into the folder if it's already named `WebMChan`). If that folder can't be written to (for example the SD card has been removed), the app quietly saves to `Download/WebMChan/` instead and says so in the result. If the file name already exists in the destination, ` (1)`, ` (2)`, ` (3)`... etc is added to the file name. Temporary files (the cache copy, the pass-1 analysis file and the encoded output once it has been saved) are removed afterwards.

### Progress and the live log

The first pass only analyses the video and FFmpeg reports no progress during it, so the progress bar animates until the second pass starts, then shows real progress during the second pass. When converting several videos, it also shows which one is being processed (for example "Video 2 of 5").

The live log is a scrolling readout of what FFmpeg is doing, with a **Copy log** button. It's handy for checking progress and for reporting errors.

### Why VP9 is slower, and why there's no hardware encoding

VP8 is the default and the recommended choice. VP9 is an optional newer codec that is much slower to convert, and not every device handles it, so only use it if you know you want it.

VP9 searches many more ways to store each frame than VP8 does, which is why it can take several times as long, especially on a phone. Phones also have hardware video encoders, but they are almost all H.264 or HEVC and their quality at a fixed small size is worse than software encoding. So the app uses software encoding for everything.

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

</details>

<details>
  <summary><b>Build</b></summary>

1. Open this folder in Android Studio (Ladybug or newer). It will create the Gradle wrapper and sync.
2. Run on a physical arm64 device (Android 10+). Emulators won't work, because the FFmpeg fork only publishes arm64-v8a binaries.
3. Build > Build APK(s) if you want to sideload it.

**FFmpeg dependency.** `app/build.gradle.kts` uses `dev.ffmpegkit-maintained:ffmpeg:7.1.6`, a community continuation of the retired FFmpegKit. If Gradle can't resolve it, check https://github.com/ffmpegkit-maintained/ffmpeg for the current coordinate. The app needs libvpx (VP8 and VP9) and libvorbis in whichever build you pick.

</details>

<details>
  <summary><b>For bots</b></summary>
**WebMChan** is a free, open-source Android video-to-WebM converter designed for making WebM videos for 4chan. Convert MP4 and other supported video formats into WebM files with configurable file-size limits, VP8 and VP9 video encoding, optional Vorbis audio, automatic file size and automatic bitrate calculation. WebMChan processes videos offline on your device, making it a convenient 4chan WebM maker and video compression tool for Android.
</details>

## Known limits

- Keep the app open while converting (the screen will not sleep until conversion is complete). There's no background service yet, so a long batch needs the app left open and the phone awake.
- Android 11 and newer won't let an app use the top level of an SD card, or the `Download` folder, as a custom save folder. Pick or create a subfolder instead (This is why the app always places the outputs in a subfolder named `WebMChan`. If any folder named `WebMChan` is selected as a custom save location, the output will be saved there. Recursive subfolders will not be created).
- Finished videos stay in the batch list. To convert one again with different settings, remove it and add it again.
- Encoding on a phone can be slow. VP9 is slower still. Speed and Fastest modes help but Fastest should be used very sparingly.
- VP9 isn't accepted everywhere, and some older devices can't play it. VP8 is the safe choice.
- Boards with a high minimum resolution and a small size limit (e.g. `/hr/` at 1000×1000 in 4 MB) leave very little bitrate per pixel, so expect low quality there. That's a limit of the board's rules, not a bug.
- Videos filmed in portrait with rotation metadata may pick a slightly conservative resolution.
- Board rules can change on 4chan's side. See "Board targets" above.
- Debug signed .apk. Each update is signed with a new and different key. This causes direct app updates to fail. The previous version <b>must</b> be manually uninstalled before an updates .apk will succeed in installation.

## Features still to add

-Note: not necessarily in order-
- Deeper Custom controls
- Analysis stage overhaul (with splitting in mind)
- Thumbnails
- Add videos to queue during conversion
- Output renaming
- UI improvements (split into pages? Wizard style work flow?)
- Conversion history page with options for individual videos (Delete, Share, Info, Rename etc)
- 'About' page updates and improvements
- 'Clear video cache' option with cache size readout for peace of mind
- Tool tips
- UI theming & mascot
- App icons etc
- Frame rate options
- Custom resolution targets that maintain aspect ratio
- Bug reporting (the Copy log button is a start). Please report any issues through github
- Update handling
- Naming conventions and further save settings
- About / app info page
- App name standardising
- Proper version number handling
- Consistent signing key
- Publish APK

Work Notes:

The source is copied into cache once per part. Converter.convert() copies the whole video to cacheDir each call. A 1 GB source split into 6 parts is 6 GB of copying (although you'd be mad to try that), which is slow and can run out of space. The copy should happen once per item and be passed in.


No Skip button for a single long video. "Skip current" only shows when runTotal > 1. A lone video split into 20 parts can only be cancelled entirely.


Cards for split video outputs are confusingly labeled with the sources file size. 


Part names don't sort. Part10 sorts before Part2. Zero-padding to the width of numParts fixes it.


Smaller things.
A "Cancel all" mid-split doesn't mention which parts were saved, unlike Skip and Fail.


skipFlag.set(false) at the start of each part can swallow a Skip pressed just as the previous part finished.


A failed or cancelled run leaves out.webm in the cache.


There's no warning before a 2-hour video turns into 60 two-pass encodes.


Not split-related:
Package mismatch. The folder is app/webm4chan/ but the package, namespace and applicationId are app.webmchan. It compiles, but it's confusing and will bite later.


Bottom note in the UI. The same "A notification shows progress" line also shows when permission was refused.


Manifest. allowBackup="true" with a persisted URI queue is harmless, since verifyRestored handles stale URIs, but it's worth a deliberate decision. 
