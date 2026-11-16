# V59 Preview — local Whisper vs Google AI Edge Gallery comparison

**Only English AI Tutor Preview** (`com.mihan.englishaitutor.v2.preview`) is changed. Upgrade from V58 Preview without uninstalling. Production V50, Gemini, movie archive and Whisper/GTCRN/CTC models are untouched.

## Supported and unsupported

- Google AI Edge Gallery has an **Audio Scribe** feature. Its traditional audio-file picker accepts local `.wav` clips (up to **30 seconds**); it can transcribe offline with a compatible on-device model such as Gemma 3n.
- The stock Gallery AndroidManifest exposes launcher and browsable view intents, but **does not expose a public Audio Scribe transcription service or Android activity-result contract**. Installing Gallery on the same phone does **not** give other apps automatic access to its model, data or ASR output. The Audio Scribe model cannot be silently borrowed from its private app storage. Therefore the comparison is a **transparent assisted manual round-trip**, not an invented automatic API. No external service, API key, billing or private permissions are added.
- Google's newer MediaPipe Audio Transcriber and ML Kit GenAI Speech Recognition provide SDK routes for **future native integration**; the Gallery app itself does not currently export those as an inter-app service. Different SDK models are not necessarily the same as the model installed in the user's Gallery.
- Gallery Audio Scribe transcriptions copied as plain text have **no trustworthy individual word timing offsets** to import into this app. V59 deliberately leaves Whisper original dialogue start/end untouched. If Gallery text is selected, the old Whisper word alignment is **cleared for that selected dialogue** to avoid presenting incorrect per-word timestamps. No automatic claim of better synchronized word karaoke is made.

## User-controlled flow

1. Load/select the movie and pause on the dialogue you want to compare.
2. Go to the app's advanced tools (Settings tab) and tap **🔬 Whisper / AI Edge**.
3. The dialog shows the **original Whisper text** for exactly the selected line. Tap **۱. ذخیره همان صدا به WAV** and choose a local folder, ideally **Downloads**, through Android's Storage Access Framework. The output WAV is the exact original movie PCM at 16kHz mono 16-bit with 200ms optional padding and a 30s maximum — **no re-encoding**. Gallery does not support whole-film WAV transcription as one request in this mode.
4. Tap **۲. باز کردن Google AI Edge Gallery** (or open the installed app from its icon). Open **Audio Scribe**, choose a compatible downloaded model, tap **+** and select the saved WAV. Ask it to transcribe the **exact English words**, without translating or inventing context.
5. Copy Gallery's returned transcription, switch back to English AI Tutor, paste using **چسباندن متن کپی‌شده**, and **ذخیره متن AI Edge برای مقایسه**.
6. View the two alternatives side by side and choose **انتخاب Whisper** or **انتخاب متن AI Edge**. This selection affects the current line's on-screen text and the text passed to the English teacher; it persists privately for the same archive, row and original Whisper time/text version. Other dialogues and their original WAV/timing are unchanged.
7. You can switch back to Whisper any time. Comparison results remain local in private SharedPreferences, and the archived Whisper transcript is NEVER overwritten by unverified Gallery output.

## Verification

`GalleryComparisonWavTest` builds a synthetic 16kHz PCM file and proves byte-exact segment extraction, correct RIFF header, duration and 30s bound, and invalid-format rejection. V59 GitHub Actions also runs all V58 teacher/markdown regression checks, compiles the APK, restores/verifies the same pinned Whisper, Silero VAD and GTCRN assets, checks package/version and the exact previous Preview signing key, and uploads a temporary artifact only. No PR merge or release/deployment.

**Not verified until device test:** Gallery installed package/version, Audio Scribe availability/model download, WAV picker compatibility and actual transcription accuracy for the user's movie. The old user-visible speech transcription complaints still require checking real audio, and this feature does not automatically solve word timing.
