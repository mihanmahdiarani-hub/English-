# V61 Preview — download the Google AI Edge model over Wi-Fi **or mobile data**

Scope: **English AI Tutor** Preview only, package `com.mihan.englishaitutor.v2.preview`. Based on V60, no other app/project changes.

## Requested change

The user wants to download the optional ~615 MB Parakeet ASR model with SIM/mobile data, rather than requiring Wi-Fi.

1. `EdgeNativeTranscriber.enqueueDownload()` now sets `DownloadManager.Request.setAllowedOverMetered(true)`, preserving `setAllowedOverRoaming(false)` to avoid accidental roaming charges.
2. An **explicit confirmation dialog** warns that approximately 615 MB of the user's mobile data allowance may be consumed before the download begins. The button and setup text no longer say “Wi-Fi only.”
3. Existing V60 Wi-Fi-only DownloadManager requests have their original networking restriction permanently attached. V61 marks new requests with `allowsMetered=true`. On the next **user-initiated** download tap, if the previous request lacks the new flag, the old request is removed and a new mobile-enabled download is started. The previous partial download may be discarded, so the dialog warns that download can restart.
4. A completed full model is reused and never re-downloaded unnecessarily. The same content destination, original Hugging Face model URL and model-load validation are retained.
5. No Render or GitHub compute is used for this mobile download. The file is downloaded directly from Hugging Face by Android DownloadManager, and inference remains on-device with the MediaPipe SDK.
6. All other features (Whisper, Gallery comparison, Gemini teacher and Live, local archive, video tab behavior) remain untouched.

**Limits:** Android's own Data Saver, disabled mobile-data permissions, background download restrictions or network issues can still restrict an individual download. The mobile plan/data volume may incur carrier fees, and roaming remains disabled. V61 does not attempt a background/billed download without the owner's tap.

## Verification

The PR-only V61 workflow checks that `setAllowedOverMetered(true)` is present, validates re-queue of existing Wi-Fi-only requests and the cost warning, builds with the same Preview keystore/signature and bundled Whisper/Silero/GTCRN models, then exports a temporary signed APK artifact. True 615 MB model download over the user's SIM needs a separate real-device test. No paid cloud service, public release or production change is authorized.
