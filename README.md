# WebMChan

Powerful but simple to use 4chan WebM converter for Android with smart targeting and plenty of features.

Pick a video/audio file (or a whole batch), choose a board, get `.webm` files that fit. You can also share one or more sources to WebMChan from another app.

Select from a list of boards and their requirement presets, or set your own size limit and resolution.

Converted videos target the highest possible quality within the board's constraints.

Videos can be easily trimmed, cut, rearranged, and cropped before conversion. A thumbnail picker is included that places a chosen frame as the first frame in the converted video.

Audio Mode makes a `.webm` out of an audio source and an image/images (or animated GIFs), and targets the highest possible sound quality.

Output is saved to `Download/WebMChan/` by default, or to a `WebMChan` folder inside any folder you choose (including one on an SD card), and can be shared straight from the app.

Everything runs locally. The app has no network permission, nothing is uploaded.

> **Status:** Pre-alpha development version. **Uninstall any previous version,** then install as you would any `.apk`. Please report bugs.

**[Download the Latest Release](https://github.com/jpeg91/WebMChan/releases/latest)**

## What it does

### Board presets

Pick the board(s) you're posting to and the app applies that board's WebM rules for you: file size, length, minimum resolution and audio. Or choose Custom and set your own size limit, and your own resolution if you like. Custom has no length limit, and the picture is never stretched.

### Automatic splitting

If a video is longer than the board's maximum length, WebMChan automatically splits it into numbered parts that fit the limit. If a split conversion is interrupted or a part fails, converting again continues from the next part rather than repeating parts already saved. While a split video converts, the card shows which part it is on (like `1/3`) and the percentage for that part.

Long parts on a small size limit get very little bitrate, and detailed footage (anything live action particularly) suffers for it. Every queued video shows what each part would get, names a quality bracket for it (see Quality brackets below), suggests ways to improve it when it's going to look poor, and has **−** and **+** buttons to split it into more (or fewer) parts. You can't go below the minimum the board needs, and parts never get shorter than 10 seconds.

### Best quality that fits

Each video is converted in two passes (analysis then encode), aiming for about 99% of the size limit, so you get as much quality as the limit allows.

### Encoder quality modes

Choose **Best quality** under Encoding and the app keeps going after the first file that fits. It converts again with a corrected bitrate, up to 7 times, until the file lands between 98% and 100% of the size limit, and keeps the fullest one. It also uses slower encoder settings for more detail per bit. This mode, when used on HD sources, can take a very long time and runs the phone hot, so expect android to throttle your CPU. A notice says so the first time you pick it.

Recommend default is **Quality** mode which is finely tuned for image quality without being too demanding on the CPU, and in a fair length of encoding time. This targets the highest possible quality at 99% of the file size limit. If it overshoots, it adjusts itself based on the amount of overshoot and tries again (up for 4 times, but in practice almost always hits the target first time, if not the second).

**Speed** mode goes for a faster encode time, whilst producing an output that appears only slightly softer than Quality mode.

A toggle exists in the settings menu for Fastest mode. This aims to get the job done as fast as possible. I don't recommend using this mode at all, but it's there. 

### Batch mode

Select one video or several, and convert them all in one go. New videos can be added to a holding pen while you adjust the settings, then sent to the queue together. Each video keeps the settings it was sent with. During a batch, you can pause immediately or after the current video finishes, resume later, skip a video (or just one part of a split video) or cancel the batch. Anything that fails or is skipped goes back to the holding pen with a note saying why.

### Editing before you convert

The **///** button on a holding pen card opens that card's pages. **Edit** can be used to trim a video, cut a video into up to 8 clips (reorder them with Earlier and Later buttons), and crop the picture (removing black bars is a great space saver, which is made up for with better output picture quality), all without touching the original. The changes are applied during conversion. Everything on the card, from the length to the split and the bitrate estimate, follows the edit. 

**Thumbnail** lets you scrub to the frame you want 4chan to display as the post's thumbnail, and it's added in front of the video as the first frame. You can also rename the output before converting.

### Audio Mode

**Menu > Audio Mode** (in purple) makes a `.webm` from an audio source (audio or video files accepted!) and an image, for the boards that allow sound. It has its own holding pen, queue, converting and finished lists, and targets the highest audio bitrate possible within the file size constraints. Only one mode (video or audio) can run conversions at a time. The sound can come from any audio file, or from the soundtrack of a video, and sharing audio to WebMChan opens it in Audio Mode.

A card is one audio file and one image. The image is the file's cover art by default (if included in the metadata), or you can pick your own, add several (they're spaced equally across the length), pick a frame from a video, or use animated GIFs. The sound quality is automatic: the highest that fits the limit, dropping as needed like video does. Long tracks are split to the time limit, and can be edited the same way as video, with tiny inaudible fades at the cuts to cover for any audio popping. There's a trim page with playback, an optional title card in the bottom-right corner, and the title and artist go into the file's metadata. Output files are named `artist - title` when the file has both.

### Simple options

Choose Best quality, Quality or Speed (Fastest is there too once you switch it on in Settings), VP8 (recommended) or VP9, whether to keep the audio and how good it should sound, and a frame-rate cap of 10, 15, 24, 30, 60 fps or a custom value. The default is 30 fps; the cap never raises a source video’s frame rate.

### Your choice of save location

Videos are saved to internal storage at `Download/WebMChan/` by default, or to a folder you choose (**Menu > Settings**), such as one on an SD card. Share them straight from the app.

### Thumbnails and previews

See a thumbnail for each queued or finished video, and tap it (a small ► marks it) to play the source or converted video.

### Finished videos

Each finished video keeps a card with its thumbnail and the settings it was made with. From the card you can share the file, rename it, delete it from your device, or press **Add to pen again** to convert the original video again with different settings. **Remove card** clears the card without touching the file.

### History

**Menu > History** keeps the card of every conversion forever, even after you remove it from the main screen, so you can keep clearing Finished. A video picked from your phone keeps a link to its source, so **Add to pen again** still works from History. A video shared into the app doesn't have its copy kept just for History, so its card says the source is lost.

### Background conversion

You can leave the app, use other apps, lock the screen and completely forget about it whilst a batch runs. If Android does manage to kill the background process (likely due to battery savers) the queue can be resumed; a notification shows progress and lets you cancel. A small status bar icon shows an hourglass while it analyses, the percentage while it encodes, and paused, failed and finished icons. The app also asks about unrestricted battery use if it isn't already allowed, since that's what stops Android killing long conversions.

### Progress and log

See what's happening while it converts: above the log, a speech bubble tells you in plain words what the app is doing (like "This will probably take about 6 min"). It shows the latest message, and tapping it opens the earlier ones for the current video. Messages that want your attention are amber. Copy the log if you need to report a problem. **Menu > Logs** keeps the full log of every conversion from the last 12 hours. The interface uses a black, white and red Persona 5 inspired theme (with Audio Mode in purple).

### Advanced settings (for testing)

**Menu > About > Advanced settings** lets you change the numbers that steer how a video is fitted into a size limit (targets, attempts, Best quality and so on). The defaults are the recommended values, **Reset to defaults** puts them back, and anything you've changed is listed at the top of the log. A **thread cap** at the top limits how many CPU threads the decoder and encoder may use. Try that if Android still stops the app under a heavy load even with unrestricted battery use allowed.

### Settings

**Menu > Settings** is a page of switches: animations off, notification options (progress, status bar icon, lock screen, finished, every failure), keep the screen on while converting, clear Finished cards on a new run, confirm before destructive actions, add shared videos straight to a running batch, and Offer Fastest mode. The save location and the Audio Mode placeholder picture are there too.

### Cache

The **Cache** page (linked from the top of Advanced settings) shows what the app stores, with sizes. **Safe wipe** clears the working files and anything no card uses. **Full cache wipe** also clears every kept analysis and thumbnail, but keeps your cards, lists and any shared copies in use. **Reset app** removes everything. Each one asks first and says how much it frees.



<details>
  <summary><b>Screenshot</b></summary>

<img width="1080" height="6137" alt="Screenshot_20261110-002341_WebMChan" src="https://github.com/user-attachments/assets/e8ab1b82-4ca7-40e7-b40a-4c7ded00f002" />

</details>

<details>
  <summary><b>How it works</b></summary>

The app is a front end for FFmpeg (the community `ffmpegkit-maintained` build). Nothing leaves the device.

### Batch queue

Use **Choose videos** to select one video or several, and **Add videos** to keep adding more. You can also share videos to WebMChan from another app; shared videos are added to the holding pen (or straight to a running batch, if you turn that on in Settings). Videos in the holding pen wait while you adjust the settings, and are sent to the queue when you press **Convert** (or **Add to running batch** while one is running). The same video can be added as many times as you like, for example to convert it again with different settings; each one gets its own card.

Before conversion, you can reorder queued videos (▲ ▼), remove them (✕), or return them to the holding pen. Each card shows its details and any warnings, such as being split into parts for the board's length limit or scaled up to its minimum resolution. Each card also has the split controls described in step 2. Videos in the holding pen follow the settings on screen. Once a video is sent to the queue it keeps the settings it was sent with, and you can return it to the holding pen to change them. The queue is converted from the bottom up, so the video lowest down goes next and is tagged NEXT.

Videos are converted one after another, each with its own settings. If a video fails, it's marked as failed and the rest carry on. **Pause now** pauses the batch, while **Pause after** lets the current video finish first; use **Resume** to carry on. The video that was converting starts again, from its first unsaved part if it was split. **Skip video** drops the video being converted and moves to the next, **Skip part** (split videos only) drops just that part and carries on with the next one, and **Cancel all** stops the whole batch. A video that fails, is skipped or is cancelled goes back to the top of the holding pen with its message. The exception is a split video with some parts already saved, which keeps its place in the queue so Convert can carry on from the next part. Each finished video has its own **Share** button, and **Share all** appears once two or more finished files are saved.

Finished videos stay in the Finished list until you remove them (**Remove card** or **Clear finished**), and stay in History after that. Pressing Convert again only processes the ones that haven't finished, which also lets you retry failures. To convert a finished video again with different settings, press **Add to pen again** on its card, change the settings and send it through again; the finished card stays where it is.

### 1. Reading the video

When you pick videos, the app asks FFmpeg's probe tool for each one's length, width, height, frame rate, colour properties and whether it has an audio track. It also creates a thumbnail for the queue card; tapping the thumbnail plays the source video. Android only lets an app read a picked video for a short while, so the app asks for a lasting read grant on each one. That's what lets a restored queue still open its videos after the app has been stopped.

When you tap Convert, the videos are processed one at a time: each source is copied into the app's private cache so FFmpeg can read it as an ordinary file (after checking there's enough free space), and that copy is deleted afterwards. One copy serves every part and every attempt for that video. A video keeps its lasting read grant (and, for a shared video, its copy in the app's storage) for as long as a card for it is in the holding pen, the queue or the Finished list, so it can always be added again.

### 2. Applying the board's rules

The board you pick supplies four numbers: the file size limit, the maximum length, the minimum resolution and whether audio is allowed. Boards with identical rules are grouped into one option, and each option lists its limits and the boards it covers, for example `4 MB · Min 500×500 · 120 s` for `/hc/` `/s/`. Custom has its own size limit, an optional resolution of its own (see step 4), no minimum resolution and no length limit.

**Length and splitting.** The app aims a second under each board's limit (a 300 s board is treated as 299 s) as a safety margin. The screens still show the board's real limit. Custom has no length limit, so a Custom video is never split. If the clip is longer than that, it's split into parts, and the parts are worked out like this:

1. The number of parts is the clip length divided by the limit, rounded up. An overshoot of half a second or less is trimmed off the end instead of becoming another part.
2. The clip is divided **evenly** between them. A 250 s video on a 120 s board becomes 3 parts of about 83.3 s, rather than 119 s + 119 s + a 12 s scrap.
3. Each part is encoded from its own slice of the source and saved separately, with numbered filenames.

If a split conversion is interrupted or a part fails, pressing Convert again continues from the next part rather than repeating parts already saved. That only works if the clip would still be split the same way (same number of parts means identical boundaries), so if you change the board in the meantime it starts again from part 1.

**Splitting further.** Each queued card shows what its parts would come out as: the video bitrate and frame height each part would get, worked out exactly as steps 3 and 4 below do it. The **−** and **+** buttons add splits on top of the board's minimum, or take them away again. It's stored as a number of *extra* parts, so it survives a change of board. Parts are never allowed to be shorter than 10 seconds, and the buttons lock while a batch runs and once a video has parts saved.

Each card also names a quality bracket for that estimate (see Quality brackets below), so you can see when more splits would help. Adding splits raises the estimate.

### 3. Working out the bitrate

A WebM's size is roughly its bitrate multiplied by its length, so the app works backwards from the limit:

1. The target size is **99% of the limit** on the first attempt (98.5% in Best quality mode, which aims there on every attempt). The small gap covers file overhead and the encoder's tendency to overshoot slightly. If it does overshoot, retries aim lower (see step 7).
2. That size, divided by the clip (or part) length, gives the total bitrate available.
3. If audio is being kept, the sound quality you chose (64 kbps by default) is taken off for it. Before the first attempt the app encodes just the audio to see what it really costs, since Vorbis often comes out under its nominal bitrate, and plans the video around that. The rest is the **video bitrate.** If that would leave the video starved, the audio is allowed to drop, as low as 32 kbps (or the chosen quality, if that is lower), before giving up.
4. If the video bitrate would still be under 40 kbps, the app stops and tells you the clip is too long for this limit. Removing audio gives the video more room.

For example, a 60 s clip with no audio on a 4 MB board gets roughly 554 kbps of video. A 280 s clip on the same limit gets only about 119 kbps, which is why long clips on a small limit look so rough, and why splitting them more helps.

Removing audio improves quality: all of that bitrate goes to the picture.

### 4. Choosing the frame size

More pixels need more bitrate to look good. The app measures this as **bits per pixel**: the video bitrate divided by the number of pixels per second (width × height × frame rate, using the selected frame-rate cap). It tries sizes from large to small, using the source's own height plus 1080, 720, 480, 360 and 240, and takes the first one that gets at least 0.06 bits per pixel (0.12 in Fastest mode, which is why Fastest ends up with smaller frames). It never goes above 1080p, never above the source's own size, and never below the board's minimum.

For example, a 1080p 16:9 clip at 554 kbps comes out at 360p: 480p would need about 740 kbps to reach 0.06 bits per pixel, 360p needs about 415.

**Easy clips.** A very simple clip (a logo, a test pattern, flat animation) can't use all of its bitrate. The encoder hits its lowest setting and the file comes out well under the size limit, so shrinking it by the rule above would be for nothing. To avoid that, the app also guesses from the source's own bitrate how little the encoder would need at each frame size, and picks the biggest size whose guess fits within 70% of the video bitrate. This only ever makes the frame size bigger, and isn't used in Fastest mode or with a Custom resolution. The guess is rough, so the log shows it next to what the encoder really used.

**Custom resolution.** With the Custom target you can set a width, a height or both (16 to 7680 px). The picture is scaled to fit inside that size and keeps its shape, so nothing is stretched, cropped or given bars. With one side set the other follows. With neither set the size is picked automatically as above. When you set a size the automatic pick is skipped, and the size limit only steers the bitrate.

If the source is below the board's minimum, it's scaled **up**. The scaling keeps the original aspect ratio and uses even width and height, as the encoder requires. Frame rates above the selected cap are reduced to that cap; lower frame rates are kept as they are. The default cap is 30 fps, with presets for 10, 15, 24 and 60 fps and a custom value from 1 to 120 fps.

### 5. Encoding in two passes

The video is encoded twice:

- **Pass 1** is analysis only. The encoder watches the whole clip and records where the hard-to-compress scenes are. No file is produced, and FFmpeg reports no progress during this pass.
- **Pass 2** is the real encode. Using what pass 1 learned, it spreads the bitrate across the clip, spending more on busy scenes and less on simple ones. The result is more consistent quality and a file much closer to the target size than a single pass would give.

Both passes use the same filters and settings, and each part is cut from the source by seeking to its start. Because the video is being re-encoded anyway, the cuts land on the exact frame. If the card has been edited, the clips are joined by a filter graph that also applies the crop, and a picked thumbnail frame is added in front as the first frame.

**The analysis is reused.** Pass 1 only looks at the picture, so its result doesn't depend on the bitrate being aimed for. The app keeps each analysis as a small file in its private storage, and any later attempt (or any later conversion of the same video at the same size, frame rate, codec and effort) skips straight to pass 2. In Best quality that saves a pass 1 on every retry. Pausing, cancelling or using **Add to pen again** doesn't lose it either. An analysis that was cut short is never used, and one is deleted once no card in the holding pen, the queue or Finished uses that video any more. The log says when pass 1 was skipped.

The encoder settings depend on what you chose:

| Setting | Encoder effort | Look-ahead | Frame size |
|---|---|---|---|
| Best quality | `cpu-used 1` | 25 frames | normal |
| Quality | `cpu-used 2` | 25 frames | normal |
| Speed | `cpu-used 4` | 25 frames | normal |
| Fastest | `cpu-used 5` | 8 frames | smaller |

"Encoder effort" is how hard the encoder searches for efficient ways to store the picture. It has nothing to do with CPU cores. Higher is faster but a little less efficient, so quality drops slightly at the same bitrate. (The thread cap in Advanced settings is what limits actual CPU cores.)

**Best quality** also uses hidden "alt-ref" frames. The encoder blends several neighbouring frames into a cleaned-up reference frame that is never shown, and the frames around it then need fewer bits. FFmpeg prints a harmless "Mismatching timestamps" warning for each one, so the app swaps those for a single note in the log. Likewise, the repeated "Late SEI" notices that some H.264 videos cause are collapsed into one note.

In plain terms: **Best quality** is the slowest and gives the most detail for the size. **Quality** is the default and targets the ideal balance between the quality of the image and the speed of the encode. **Speed** encodes noticeably faster at a small cost in very fine detail. **Fastest** (switched on under Settings) is faster again, but it uses a lower encoder effort and picks smaller frame sizes, so it looks a lot softer than Quality or Speed. I don't recommend using it for anything other than benchmarking. All modes stay just under the size limit.

When VP9 is selected, a few extra settings apply:
- **Multithreading.** Row-based multithreading is turned on, and Fastest adds tile columns so more cores can work at once.
- **Plain 8-bit colour.** The video is forced to standard 8-bit colour so that most players can decode it.

The output of both codecs is explicitly tagged as standard SDR video (BT.709). Without the tag, a strict player can misread the colours of a file that's carrying a leftover tag from its source. A full-range source is converted to the normal range, and HDR (PQ or HLG) is tone-mapped to ordinary colours when the phone's FFmpeg build can do it, so it doesn't come out dark and washed out. If it can't, the HDR is kept as it is and the log readout says so.

Metadata (titles, GPS and so on) and subtitles are stripped from the output.

### 6. Audio

If audio is kept, it's encoded as Vorbis during pass 2 at the sound quality you chose: 16 kbps (mono), 32, 64 (the default, stereo), 128 or 256 kbps. It's lowered if a tight budget needs it (see step 3) but never raised above your choice. The Vorbis encoder refuses some low bitrates at a typical 44.1 or 48 kHz sample rate, so for anything under 64 kbps the app first tries a half-second test encode, and drops to a lower sample rate if it has to. If no rate works, the clip fails with a message asking for a higher sound quality. Otherwise the output has no audio stream at all. Removing audio gives all the space to video quality. Boards that don't allow audio lock the switch to "remove".

### 7. Checking the size and retrying

After pass 2, the app measures the real file. If it's within the limit, it's done. If it's over, the budget is scaled down in proportion (aiming for 97.5% of the limit, a little lower than the first attempt, since the encoder has just shown it overshoots) and the clip is encoded again, up to 4 attempts in total. In practice the first attempt nearly always fits. The log shows, for each attempt, what was asked for against what came out and the encoder's final quantiser.

The proportional retry can't tell whether the video or the audio overshot. So if audio is on, the retry uses the audio's real size (measured before the first attempt, see step 3), works out how the video encoder actually responded to its bitrate, and gives the video exactly what's left of the limit. If it still doesn't fit after 4 tries, the app says so.

**Best quality retries differently.** It keeps the frame size from the first attempt so the results can be compared, and works out each next bitrate from every attempt so far, by interpolating between one that came out under the target and one over it. It keeps the fullest file that fits and deletes the rest as it goes, so only two encodes ever exist at once. It stops when a file lands between 98% and 100% of the limit, when asking for more bitrate no longer makes the file any bigger (the encoder is already at its lowest quantiser), when there's no new bitrate left to try, or after 7 attempts. If a later attempt fails, the best result so far is kept.

### 8. Saving

Each finished file is saved using the original file name (or the name you typed on the card; in Audio Mode it's the file's `artist - title` when it has both) plus a short marker for the audio and speed settings used (for example `-MQ` for no audio at Quality, `-AF` for audio kept at Fastest, or `-AB` for audio kept at Best quality), and ends with the app's tag. Split videos also include a numbered part marker, zero-padded when there are ten or more so they sort properly: `clip-Part02-MQ-WebMChan_for_Android.webm`. The **Share** button opens Android's share sheet. Tapping a thumbnail on a finished card plays the converted video.

By default the file goes to internal storage at `Download/WebMChan/`. **Choose folder** (under Settings) lets you pick somewhere else, such as a folder on an SD card, and **Use default** switches back. If you've chosen a folder, it's written through Android's Storage Access Framework into a `WebMChan` subfolder there (or straight into the folder if it's already named `WebMChan`). If that folder can't be written to (for example the SD card has been removed), the app quietly saves to `Download/WebMChan/` instead and says so in the result. If the file name already exists in the destination, ` (1)`, ` (2)`, ` (3)`... etc is added to the file name. Temporary files (the cache copy and the encoded output once it has been saved) are removed afterwards; the pass-1 analysis is kept for as long as a card for that video exists (see step 5).

Each part gets its own card in the Finished list, with a thumbnail taken from the converted file and its own length, size and resolution read back from it. A saved file can be renamed or deleted from its card. **Rename** keeps the `.webm` ending and refuses names Android can't store, and **Delete file** asks first and leaves the card where it is.

### Progress and the live log

The first pass only analyses the video and FFmpeg reports no progress during it, so the card shows an hourglass until the second pass starts, then the percentage. A split video shows the percentage for each part, with which part it is (like `1/3`). When converting several videos, it also shows which one is being processed (for example "Video 2 of 5"). Conversion runs in a foreground service, so you can lock the screen or leave the app while it runs. The notification shows progress and has a Cancel button. Android notification permission is needed to see notifications.

The live log is a scrolling readout of what FFmpeg is doing, with a **Copy log** button. It's handy for checking progress and for reporting errors. It's cleared when a new batch starts, so the complete log of each conversion is also saved to **Menu > Logs**. That keeps the last 12 hours (up to 100 conversions, failed and cancelled ones included), each with its source details, settings and result, and its own **Copy log** button. A very long log keeps its start and its most recent lines, and says how many it left out.

### Staying alive, and remembering the queue

The batch runs in the app's process, not in the screen, so closing the screen doesn't stop it. The foreground service gives the process priority and holds a wake lock so the CPU isn't throttled while the screen is off. Android 15 ends this kind of service after about 6 hours, and the app cancels cleanly rather than crashing if that happens.

The queue, the Finished list and History are saved to small files in the app's private storage whenever they change, and loaded again when the app starts. A batch that was running when the app stopped comes back paused, ready to **Resume**. Each queued video keeps its own settings through this (one saved by an older version has none, so it goes back to the holding pen with a note). After a restart the app checks that each queued video can still be read and that each finished file is still there (a card whose file has gone loses its Share button and says why), and anything that was mid-conversion when the app stopped is marked so Convert picks it back up. App data is left out of Android backups.

### Audio Mode, in more detail

Audio Mode uses the same board rules and splitting as video, with the picture as a very low frame rate video (2 fps by default; 1, 2, 5, 10, 24 or a custom value) and the sound as the main thing to fit. The sound bitrate is automatic: the app picks the highest that leaves the picture enough room, drops it if the file comes out too big, and never goes above what the source's own bitrate can use. If only a very low rate would fit, the card says so and suggests more parts. Split tracks get 20 ms fades at the cuts, just enough to hide the click, and nothing is done to the loudness.

A picture is kept as a JPEG in the app's storage. An animated GIF is kept as the GIF itself and made by its own encoder, which gives the GIF video its share of the size limit and lowers that before it lowers the sound. Title cards and the "WebMChan" mark are drawn as JPEGs.

### Quality brackets

Each queued card works out how many bits the video would get per pixel (the video bitrate divided by pixels per second, so frame size and frame rate both count) and says which bracket that falls in and what kind of video it suits. For example: "Quality Bracket: Web Animation. Suits simple, flat-colour animation and memes. Live action will look poor." The three lowest brackets are shown in a warning colour and add up to two ways to improve it: split into more parts, lower the frame rate, decrease sound quality, try VP9 or lower the custom resolution. Only what can still be changed is offered.
 
Because it's bits per pixel, a 60 fps video needs twice the kbps of a 30 fps one to land in the same bracket, and a bigger frame needs more too. Bigger frames also get more out of each bit, so the figure is nudged up for them before it's compared with the table (roughly in step with the frame height, so 720p counts about three times over). A 240p frame is unchanged. The kbps column below is only a guide, for a 240p frame at 30 fps.
 
| Bracket | Bits per pixel | About (240p, 30 fps) | Suits |
|---|---|---|---|
| Still Image | under 0.026 | under 80 kbps | Still images and slideshows |
| Web Animation | 0.026 to 0.041 | 80 to 126 kbps | Simple, flat-colour animation and memes |
| Pro Animation | 0.041 to 0.065 | 126 to 200 kbps | Clean animation with smooth motion |
| Old Web Video | 0.065 to 0.13 | 200 to 400 kbps | Live action like an old web clip, fine for talking heads and moderate motion |
| Quality | 0.13 to 0.28 | 400 to 860 kbps | Clean-looking live action, fast motion may show some blockiness |
| High Quality | 0.28 to 0.62 | 860 to 1,900 kbps | Live action with clean detail, including fast motion |
| Ultra High Quality | over 0.62 | over 1,900 kbps | Detailed, grainy or fast-moving footage with room to spare |
 
The app can't tell live action from animation, so treat the bracket as a guide, not a rule. The thresholds are adjustable in Advanced settings. 

### Advanced settings

These are the numbers behind the steps above. Every value is kept inside an allowed range, so a typo can't break anything. Changes apply from the next conversion.

| Setting | Default | Allowed |
|---|---|---|
| Thread cap (decoder and encoder) | 0 (no limit) | 0 to 64 |
| First attempt target (% of limit) | 99 | 80 to 110 |
| Retry target (% of limit) | 97.5 | 80 to 110 |
| Maximum attempts | 4 | 1 to 8 |
| Lowest audio bitrate (kbps) | 32 | 8 to 256 (never above the chosen sound quality) |
| Lowest video bitrate (kbps) | 40 | 10 to 500 |
| Detail threshold, Quality and Speed (bits per pixel) | 0.06 | 0.01 to 0.5 |
| Detail threshold, Fastest (bits per pixel) | 0.12 | 0.01 to 1 |
| Length grace (seconds) | 1 | 0 to 10 |
| Trim tolerance (seconds) | 0.5 | 0 to 5 |
| Easy clips: frame size budget (% of video bitrate) | 70 | 0 to 100 (0 turns it off) |
| Easy clips: prediction factor | 3.5 | 1 to 10 |
| Best quality: maximum attempts | 7 | 1 to 12 |
| Best quality: accepted from (% of limit) | 98 | 80 to 100 |
| Best quality: target (% of limit) | 98.5 | accepted value to 100 |
| Best quality: encoder effort (`cpu-used`) | 1 | 0 to 3 |
| Best quality: alt-ref blend frames | 7 | 0 to 15 |
| Best quality: alt-ref blend strength | 5 | 0 to 6 (0 turns the blending off) |
| Quality bracket starts (bits per pixel): web animation, pro animation, old web video, quality, high quality, ultra high quality | 0.026, 0.041, 0.065, 0.13, 0.28, 0.62 | each at least the one before |
| Quality bracket: frame size correction (exponent) | 0.5 | 0 to 1 (0 turns it off) |

The audio bitrate itself is chosen under **Sound** on the main screen.

Targets above 100% are there for testing: the file is expected to go over the limit, so it counts as too big.

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

**FFmpeg dependency.** `app/build.gradle.kts` uses `dev.ffmpegkit-maintained:ffmpeg:7.1.7`, a community continuation of the retired FFmpegKit. If Gradle can't resolve it, check https://github.com/ffmpegkit-maintained/ffmpeg for the current coordinate. The app needs libvpx (VP8 and VP9) and libvorbis in whichever build you pick. The editor's and trim page's previews use AndroidX Media3 ExoPlayer, and the app still has no network permission.

</details>

<details>
  <summary><b>For bots</b></summary>
**WebMChan** is a free, open-source Android video-to-WebM converter designed for making WebM videos for 4chan. Convert MP4 and other supported video formats into WebM files with configurable file-size limits, VP8 and VP9 video encoding, optional Vorbis audio, automatic file size and automatic bitrate calculation. It can also make WebM files from audio plus a picture or animated GIF. WebMChan processes videos offline on your device, making it a convenient 4chan WebM maker and video compression tool for Android.
</details>

## Known limits

- Background conversion relies on Android's foreground-service and notification support. On Android 13 and newer, allow notifications so you can see conversion progress and use the notification controls.
- Android 11 and newer won't let an app use the top level of an SD card, or the `Download` folder, as a custom save folder. Pick or create a subfolder instead (This is why the app always places the outputs in a subfolder named `WebMChan`. If any folder named `WebMChan` is selected as a custom save location, the output will be saved there. Recursive subfolders will not be created).
- Best quality is very slow, especially on long or high-resolution videos, and VP9 is slower again. If Android stops the app under heavy load, try the thread cap in Advanced settings.
- Only one mode (Video or Audio) can convert at a time.
- Audio Mode pictures and title cards are JPEG, because the phone's FFmpeg build has no PNG decoder. Pictures you add are converted to JPEG.
- VP9 isn't accepted everywhere, and some older devices can't play it. VP8 is the safe choice.
- Boards with a high minimum resolution and a small size limit (e.g. `/hr/` at 1000×1000 in 4 MB) leave very little bitrate per pixel, so expect low quality there. That's a limit of the board's rules, not a bug.
- Board rules can change on 4chan's side. See "Board targets" above.
- Debug signed .apk. For now, each update is signed with a new and different key. This causes direct app updates to fail. The previous version <b>must</b> be manually uninstalled before an updates .apk will succeed in installation.

## Features still to add

*Not necessarily in order.*
- Improved title
- Clean up text and warnings
- Video preview during conversion
- UI improvements (Wizard style work flow?)
- 'About' page updates and improvements
- Tool tips
- Mascot
- Update handling (signed key)
- Naming convention improvements
- Custom ffmpeg arguments mode
- Video conversion card top trumps/turn based battles
- Possible ports of the engine; webapp, Linux etc?
