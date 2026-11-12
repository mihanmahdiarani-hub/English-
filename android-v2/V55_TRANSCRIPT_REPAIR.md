# English AI Tutor V55 Preview — transcript/audio timing repair

The owner reported that English voice-to-text and dialogue timing had become unreliable. This is an isolated, conservative **V54 Preview upgrade**, NOT a change to the production V50 app or to another app.

## Source-review findings (not a verified diagnosis of the owner's particular clip)

1. Archived videos are keyed by their file SHA-256. If a transcript already exists, the app reloads it and **never runs Whisper again**. Upgrading Whisper/timing code alone cannot repair already-bad archived subtitles.
2. After word alignment, `Wav2VecAlignmentEngine` previously **replaced the entire dialogue's Whisper start/end with the timestamp of the first/last CTC-aligned word**. If the last consonant or phrase tail is missing, Media3 clip playback can cut the actor off. The actual word aligner does not independently verify the text recognized by Whisper.
3. Whisper is normally supplied GTCRN denoised speech while original movie PCM remains in the archive. GTCRN may improve speech embedded in noise or distort/suppress some voices. There was no user-selectable **A/B re-transcription** to verify this.

## V55 changes

- Adds **🛠 اصلاح متن** in the advanced tool drawer for a prepared archived movie. The user explicitly chooses:
  - **بازسازی با صدای اصلی فیلم** (try raw original WAV without GTCRN)
  - **بازسازی با صدای GTCRN** (for noisy sources)
  - **بازگرداندن متن قبلی** (available after a repair is attempted)
- Re-runs Whisper for the selected **already-archived movie**, ignoring its saved transcript only in the explicitly requested repair path. Does NOT upload media, recopy or delete the original video, original WAV, speech WAV, other saved movies, or the owner's Gemini key.
- Creates `transcript.before-repair.json` and `transcript.before-repair.source` **before** reanalysis. A failure must leave the existing transcript untouched. Restore validates archive ID and version and reactivates the correct audio-source marker.
- Preserves the existing movie playback position during reanalysis. Does not reset progress to zero on completion. Restored transcript resumes at the prior playback time.
- Word-level CTC is run against the **same raw/enhanced WAV that generated Whisper's text**. CTC can supply word timestamps only when they are acoustically plausible and **fully within original sentence bounds**. It can NO LONGER shorten or stretch the dialogue time boundaries.
- All existing V54 Gemini text/live, local Whisper base.en, Media3 playback, local film archive, word tap, original signing certificate and package name remain.
- `versionCode = 55` with the same Preview package `com.mihan.englishaitutor.v2.preview` and exact V51–V54 preview signing certificate. Never uninstall old Preview to install V55; that would delete the private archive.

## How to evaluate fairly

1. Install V55 over V54 Preview **without uninstalling**.
2. Open the previously faulty movie. It will initially load its **old saved transcript** (deliberately, until you choose).
3. Open the advanced tools and choose **🛠 اصلاح متن → بازسازی با صدای اصلی فیلم**. Allow time for local Whisper and background CTC. Text stays offline.
4. Compare against a **short 15–30 s passage** in the original film, including precise start/end of at least one dialogue. If GTCRN would help, explicitly choose it and compare with original.
5. If transcription becomes worse, choose **بازگرداندن متن قبلی**. The film archive and key remain.
6. Send an error example consisting of the video clip, expected English words (if known), actual generated text and specific second at which highlighted transcript diverges. Diagnostic logs alone (e.g. `WHISPER success`, `CTC aligned`) are not a measurement of transcription correctness.

No model or audio asset changed here. Actual speech recognition quality is still constrained by the tiny **Whisper base.en** on-device model and acoustic mix. This repair removes risky timing mutation and allows user-controlled comparison; it **does not guarantee perfect transcripts or prove that it fixes the user's particular movie without a clip**.

## CI and release scope

A dedicated PR-only GitHub Actions workflow compiles Java/Kotlin, verifies the full offline models and preserved Preview signing certificate, checks package/version and confirms the repair/backup/CTC invariants, and emits a temporary Actions APK artifact only. No public release, merge, production edit, backend, Firebase, paid API activation, or billing changes are authorized.
