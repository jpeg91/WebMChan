# WebMChan

No fuss 4chan WebM converter for Android with smart targeting and powerful controls.

Pick a video (or a whole batch), choose a board, get `.webm` files that fit. You can also share one or more videos to WebMChan from another app.

Select from a list of boards and their requirement presets (or set your own).

Converted videos target the highest possible quality within the board's constraints.

Output is saved to `Download/WebMChan/` by default, or to a `WebMChan` folder inside any folder you choose (including one on an SD card), and can be shared straight from the app.

Everything runs locally. The app has no network permission, nothing is uploaded.

> **Status:** Early development version. **Uninstall any previous version,** then install as you would any `.apk`. Please report bugs.

**[Download the Latest Release](https://github.com/jpeg91/WebMChan/releases/latest)**

**Note: the rest of this readme is outdated and does not reflect the current release v0.8.50.**

## What it does

### Board presets

Pick the board(s) you're posting to and the app applies that board's WebM rules for you: file size, length, minimum resolution and audio. Or choose Custom and set your own size limit.

### Automatic splitting

If a video is longer than the board's maximum length, WebMChan splits it into numbered parts that fit the limit. If a split conversion is interrupted or a part fails, converting again continues from the next part rather than repeating parts already saved.

Long parts on a small size limit get very little bitrate, and detailed footage (anything live action) suffers for it. Every queued video shows what each part would get, warns you when it's going to look poor, and has **−** and **+** buttons to split it into more (or fewer) parts. You can't go below the minimum the board needs, and parts never get shorter than 10 seconds.

### Best quality that fits

Each video is converted in two passes (analysis then encode), aiming for about 99% of the size limit, so you get as much quality as the limit allows.

### Batch mode

Select one video or several, and convert them all in one go with the same settings. New videos can be added to a holding pen while you adjust the settings, then sent to the queue together. During a batch, you can pause immediately or after the current video finishes, resume later, skip the current video or cancel the batch.

### Simple options

Choose Quality, Speed or Fastest modes, VP8 (recommended) or VP9, whether to keep the audio, and a frame-rate cap of 24, 30, 60 fps or a custom value. The default is 30 fps; the cap never raises a source video’s frame rate.

### Your choice of save location

Videos are saved to `Download/WebMChan/` by default, or to a folder you choose, such as one on an SD card. Share them straight from the app.

### Thumbnails and previews

See a thumbnail for each queued or finished video, and tap it to play the source or converted video.

### Background conversion

You can leave the app, use other apps, lock the screen and completely forget about it whilst a batch runs. If Android does manage to kill the background process (likely due to battery savers) the queue can be resumed; a notification shows progress and lets you cancel.

### Progress and log

See what's happening while it converts, and copy the log if you need to report a problem. The interface uses a black, white and red P5-inspired theme.

### Advanced settings (for testing)

**About > Advanced settings** lets you change the numbers that steer how a video is fitted into a size limit (targets, attempts, audio bitrate and so on) without rebuilding the app. The defaults are the recommended values, **Reset to defaults** puts them back, and anything you've changed is listed at the top of the log.

<details>
  <summary><b>Screenshot**(OUTDATED)**</b></summary>

<img alt="29962" src="https://github.com/user-attachments/assets/3aa4d794-3456-41cc-a0b1-151bdba11cbb" />

<img alt="29963" src="https://github.com/user-attachments/assets/e5d84473-9d55-4733-adaf-0ca250913601" />


</details>

<details>
  <summary><b>How it works</b></summary>

The app is a front end for FFmpeg (the community `ffmpegkit-maintained` build). Nothing leaves the device.

### Batch queue

Use **Choose videos** to select one video or several, and **Add videos** to keep adding more. You can also share videos to WebMChan from another app; shared videos are added to the holding pen. Videos in the holding pen wait while you adjust the settings, and are sent to the queue when you start or add them to the batch. A video that is already in the list is ignored.

Before conversion, you can reorder queued videos (▲ ▼), remove them (✕), or return them to the holding pen. Each card shows its details and any warnings, such as being split into parts for the board's length limit or scaled up to its minimum resolution. Each card also has the split controls described in step 2.

Every video in the queue is converted with the same settings, one after another. If a video fails, it's marked as failed and the rest carry on. **Pause now** pauses the batch, while **Pause after this conversion** lets the current video finish first; use **Resume** to carry on. **Skip current** drops the video being converted and moves to the next, and **Cancel all** stops the whole batch. Each finished video has its own **Share** button, and **Share all** appears once two or more finished files are saved.

Finished videos stay in the list. Pressing Convert again only processes the ones that haven't finished, which also lets you retry failures. To convert a finished video again with different settings, remove it and add it again.

### 1. Reading the video

When you pick videos, the app asks FFmpeg's probe tool for each one's length, width, height, frame rate and whether it has an audio track. It also creates a thumbnail for the queue card; tapping the thumbnail plays the source video. Android only lets an app read a picked video for a short while, so the app asks for a lasting read grant on each one. That's what lets a restored queue still open its videos after the app has been stopped.

When you tap Convert, the videos are processed one at a time: each source is copied into the app's private cache so FFmpeg can read it as an ordinary file (after checking there's enough free space), and that copy is deleted afterwards. One copy serves every part and every attempt for that video.

### 2. Applying the board's rules

The board you pick supplies four numbers: the file size limit, the maximum length, the minimum resolution and whether audio is allowed. Boards with identical rules are grouped into one option, and each option lists its limits and the boards it covers, for example `4 MB · Min 500×500 · 120 s` for `/hc/` `/s/`. Custom has its own size limit, no minimum resolution and a 300 s length cap.

**Length and splitting.** The app aims a second under each board's limit (a 300 s board is treated as 299 s) as a safety margin. The screens still show the board's real limit. If the clip is longer than that, it's split into parts, and the parts are worked out like this:

1. The number of parts is the clip length divided by the limit, rounded up. An overshoot of half a second or less is trimmed off the end instead of becoming another part.
2. The clip is divided **evenly** between them. A 250 s video on a 120 s board becomes 3 parts of about 83.3 s, rather than 119 s + 119 s + a 12 s scrap.
3. Each part is encoded from its own slice of the source and saved separately, with numbered filenames.

If a split conversion is interrupted or a part fails, pressing Convert again continues from the next part rather than repeating parts already saved. That only works if the clip would still be split the same way (same number of parts means identical boundaries), so if you change the board in the meantime it starts again from part 1.

**Splitting further.** Each queued card shows what its parts would come out as: the video bitrate and frame height each part would get, worked out exactly as steps 3 and 4 below do it. The **−** and **+** buttons add splits on top of the board's minimum, or take them away again. It's stored as a number of *extra* parts, so it survives a change of board. Parts are never allowed to be shorter than 10 seconds, and the buttons lock while a batch runs and once a video has parts saved.

The card warns when the estimated video bitrate falls under 130 kbps (changeable in Advanced settings). That's roughly where even the smallest frame size stops looking acceptable for detailed footage. The app can't tell live action from animation, so treat it as a nudge, not a rule.

### 3. Working out the bitrate

A WebM's size is roughly its bitrate multiplied by its length, so the app works backwards from the limit:

1. The target size is **99% of the limit** on the first attempt. The small gap covers file overhead and the encoder's tendency to overshoot slightly. If it does overshoot, retries aim lower (see step 7).
2. That size, divided by the clip (or part) length, gives the total bitrate available.
3. If audio is being kept, 64 kbps is taken off for it. The rest is the **video bitrate.** If that would leave the video starved, the audio is allowed to drop, as low as 32 kbps, before giving up.
4. If the video bitrate would still be under 40 kbps, the app stops and tells you the clip is too long for this limit. Removing audio gives the video more room.

For example, a 60 s clip with no audio on a 4 MB board gets roughly 554 kbps of video. A 280 s clip on the same limit gets only about 119 kbps, which is why long clips on a small limit look so rough, and why splitting them more helps.

Removing audio improves quality: all of that bitrate goes to the picture.

### 4. Choosing the frame size

More pixels need more bitrate to look good. The app measures this as **bits per pixel**: the video bitrate divided by the number of pixels per second (width × height × frame rate, using the selected frame-rate cap). It tries sizes from large to small, using the source's own height plus 1080, 720, 480, 360 and 240, and takes the first one that gets at least 0.06 bits per pixel (0.12 in Fastest mode, which is why Fastest ends up with smaller frames). It never goes above 1080p, never above the source's own size, and never below the board's minimum.

For example, a 1080p 16:9 clip at 554 kbps comes out at 360p: 480p would need about 740 kbps to reach 0.06 bits per pixel, 360p needs about 415.

If the source is below the board's minimum, it's scaled **up**. The scaling keeps the original aspect ratio and uses even width and height, as the encoder requires. Frame rates above the selected cap are reduced to that cap; lower frame rates are kept as they are. The default cap is 30 fps, with presets for 24 and 60 fps and a custom value from 1 to 120 fps.

### 5. Encoding in two passes

The video is encoded twice:

- **Pass 1** is analysis only. The encoder watches the whole clip and records where the hard-to-compress scenes are. No file is produced, and FFmpeg reports no progress during this pass.
- **Pass 2** is the real encode. Using what pass 1 learned, it spreads the bitrate across the clip, spending more on busy scenes and less on simple ones. The result is more consistent quality and a file much closer to the target size than a single pass would give.

Both passes use the same filters and settings, and each part is cut from the source by seeking to its start. Because the video is being re-encoded anyway, the cuts land on the exact frame.

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
- **Plain 8-bit colour.** The video is forced to standard 8-bit colour so that most players can decode it.

The output of both codecs is explicitly tagged as standard SDR video (BT.709). Without the tag, a strict player can misread the colours of a file that's carrying a leftover tag from its source.

Metadata (titles, GPS and so on) and subtitles are stripped from the output.

### 6. Audio

If audio is kept, it's encoded as Vorbis at 64 kbps stereo during pass 2, or lower if a tight budget needs it (see step 3). Otherwise the output has no audio stream at all. Removing audio gives all the space to video quality. Boards that don't allow audio lock the switch to "remove".

### 7. Checking the size and retrying

After pass 2, the app measures the real file. If it's within the limit, it's done. If it's over, the budget is scaled down in proportion (aiming for 97.5% of the limit, a little lower than the first attempt, since the encoder has just shown it overshoots) and both passes run again, up to 4 attempts in total. In practice the first attempt nearly always fits.

The proportional retry can't tell whether the video or the audio overshot. So if audio is on and the file is still too big by the third attempt, the app encodes just the audio on its own to measure what it really costs (Vorbis often comes out under its nominal bitrate), works out how the video encoder actually responded to its bitrate, and gives the video exactly what's left of the limit. It's noted in the log and on the result card when that happens. If it still doesn't fit after 4 tries, the app says so.

### 8. Saving

Each finished file is saved using the original file name plus a short marker for the audio and speed settings used (for example `-MQ` for no audio at Quality, or `-AF` for audio kept at Fastest), and ends with the app's tag. Split videos also include a numbered part marker, zero-padded when there are ten or more so they sort properly: `clip-Part02-MQ-WebMChan_for_Android.webm`. The **Share** button opens Android's share sheet. Tapping a thumbnail on a finished card plays the converted video.

By default the file goes to internal storage at `Download/WebMChan/`. **Choose folder** lets you pick somewhere else, such as a folder on an SD card, and **Use default** switches back. If you've chosen a folder, it's written through Android's Storage Access Framework into a `WebMChan` subfolder there (or straight into the folder if it's already named `WebMChan`). If that folder can't be written to (for example the SD card has been removed), the app quietly saves to `Download/WebMChan/` instead and says so in the result. If the file name already exists in the destination, ` (1)`, ` (2)`, ` (3)`... etc is added to the file name. Temporary files (the cache copy, the pass-1 analysis file and the encoded output once it has been saved) are removed afterwards.

Each part gets its own card in the Finished list, with a thumbnail taken from the converted file and its own length, size and resolution read back from it.

### Progress and the live log

The first pass only analyses the video and FFmpeg reports no progress during it, so the progress bar animates until the second pass starts, then shows real progress during the second pass. When converting several videos, it also shows which one is being processed (for example "Video 2 of 5"). Conversion runs in a foreground service, so you can lock the screen or leave the app while it runs. The notification shows progress and has a Cancel button. Android notification permission is needed to see notifications.

The live log is a scrolling readout of what FFmpeg is doing, with a **Copy log** button. It's handy for checking progress and for reporting errors.

### Staying alive, and remembering the queue

The batch runs in the app's process, not in the screen, so closing the screen doesn't stop it. The foreground service gives the process priority and holds a wake lock so the CPU isn't throttled while the screen is off. Android 15 ends this kind of service after about 6 hours, and the app cancels cleanly rather than crashing if that happens.

The queue and the Finished list are saved to a small file in the app's private storage whenever they change, and loaded again when the app starts. After a restart the app checks that each queued video can still be read and that each finished file is still there (a card whose file has gone loses its Share button and says why), and anything that was mid-conversion when the app stopped is marked so Convert picks it back up.

### Advanced settings

These are the numbers behind the steps above. Every value is kept inside an allowed range, so a typo can't break anything. Changes apply from the next conversion.

| Setting | Default | Allowed |
|---|---|---|
| First attempt target (% of limit) | 99 | 80 to 110 |
| Retry target (% of limit) | 97.5 | 80 to 110 |
| Maximum attempts | 4 | 1 to 8 |
| Audio analysis from attempt | 3 | 2 to 9 |
| Audio bitrate (kbps) | 64 | 16 to 192 |
| Lowest audio bitrate (kbps) | 32 | 8 up to the audio bitrate |
| Lowest video bitrate (kbps) | 40 | 10 to 500 |
| Detail threshold, Quality and Speed (bits per pixel) | 0.06 | 0.01 to 0.5 |
| Detail threshold, Fastest (bits per pixel) | 0.12 | 0.01 to 1 |
| Length grace (seconds) | 1 | 0 to 10 |
| Trim tolerance (seconds) | 0.5 | 0 to 5 |
| Low bitrate warning (kbps) | 130 | 20 to 2000 |

Targets above 100% are there for testing: the file is expected to go over the limit, so it counts as too big. Setting the audio analysis attempt above the maximum attempts turns it off.

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

- The frame-rate cap offers 24, 30 and 60 fps presets plus a custom value. It never increases a source video's frame rate.
- Background conversion relies on Android's foreground-service and notification support. On Android 13 and newer, allow notifications so you can see conversion progress and use the notification controls.
- Android 11 and newer won't let an app use the top level of an SD card, or the `Download` folder, as a custom save folder. Pick or create a subfolder instead (This is why the app always places the outputs in a subfolder named `WebMChan`. If any folder named `WebMChan` is selected as a custom save location, the output will be saved there. Recursive subfolders will not be created).
- Extra splits can't be changed while a batch is running, or once a video has some of its parts saved.
- Encoding on a phone can be slow. VP9 is slower still. Speed and Fastest modes help but Fastest should be used very sparingly.
- VP9 isn't accepted everywhere, and some older devices can't play it. VP8 is the safe choice.
- Boards with a high minimum resolution and a small size limit (e.g. `/hr/` at 1000×1000 in 4 MB) leave very little bitrate per pixel, so expect low quality there. That's a limit of the board's rules, not a bug.
- Videos filmed in portrait with rotation metadata may pick a slightly conservative resolution.
- Board rules can change on 4chan's side. See "Board targets" above.
- Debug signed .apk. Each update is signed with a new and different key. This causes direct app updates to fail. The previous version <b>must</b> be manually uninstalled before an updates .apk will succeed in installation.

## Features still to add

*Not necessarily in order.*
- Deeper Custom controls
- Improved title
- Android top bar notification icons
- Clean up text and warnings
- Background usage permission nag
- Split videos in queue show multiple sub-cards
- Video preview during conversion
- Individual queue item settings
- Music mode (High bitrate audio, low bitrate video)
- Image+audio WebM creator (.gif support?)
- Fix 'skip' button not always showing
- UI improvements (split into pages? Wizard style work flow?)
- Conversion history page with options for individual videos (Delete, Share, Info, Rename etc)
- 'About' page updates and improvements
- 'Clear video cache' option with cache size readout for peace of mind
- Tool tips
- Mascot
- App icons etc
- Custom resolution targets that maintain aspect ratio
- Pre-conversion video editing (trim, crop etc)
- Output renaming (before and after conversion)
- Extended converted video card options (clear, delete, move etc (implement 'converted videos' screen with these options first))
- Bug reporting (the Copy log button is a start). Please report any issues through github
- Update handling
- Naming conventions and further save settings
- App name standardising
- Proper version number handling
- Custom ffmpeg arguments mode
- Consistent signing key
- Possible ports of the engine to Linux etc?
