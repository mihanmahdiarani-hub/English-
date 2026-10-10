# English AI Tutor V2 — offline VAD, multi-pass speech alignment and karaoke (v48)

Applies **only** to `com.mihan.englishaitutor.v2`. Nothing is deployed to Render,
Liara, other apps or external speech servers.

## Runtime

1. Media3 keeps playing the **original video with its synchronized sound**.
2. The app reuses the original 16 kHz movie WAV and GTCRN-enhanced speech WAV.
3. Whisper extracts dialogue text offline. Silero VAD checks speech presence.
   The Wav2Vec2 CTC engine determines **actual word timings**, not VAD.
4. A failed CTC pass is retried with a larger true acoustic window and then
   a conservative VAD-proposed window. The original CTC confidence and
   maximum boundary shift checks remain in force.
5. Transcript rows which consist only of `[Music]`, `[Applause]`, etc.
   are not counted as FAILED actor dialogue. Captions with speech remain.
6. The orange underline tracks the currently spoken **CTC-confirmed word**
   using `Media3.getCurrentPosition()` converted to the original WAV clock.
   It never uses guessed word durations. An unaligned word has no moving
   orange underline; it may still be independently aligned on demand.
7. Tapping a word plays the original actor WAV slice. Tapping a sentence
   seeks Media3 to that actor phrase. An explicit timing correction remains
   available per movie.

## Local-only and privacy

- Both the MIT-licensed Silero ONNX model and GTCRN ship **inside the APK**.
- No data connection or cloud inference is used to extract, separate,
  recognize, align or animate English dialogue.
- First installation/update of the APK does require a download. Optional
  Gemini teaching chat remains online and is NOT involved in speech timing.
- Silero MIT attribution is distributed at
  `app/src/main/assets/SILERO_VAD_LICENSE.txt`.
- Silero model source:
  `snakers4/silero-vad`, commit
  `1e261b036686cd0017d500ee96acd1c4ba572a9d`,
  blob `80c5592ef1f4c9ede3e357bbd02eb863358a6a9d`,
  2,327,524 bytes. CI verifies its immutable Git blob before APK build.

## Validation and limitations

- Offline Java tests cover speech-window conservatism, soundtrack captions,
  real word-to-player timing, unknown/low-confidence words and shifted clocks.
- A real ONNX CPU smoke test confirms Silero inputs/state/output.
- GitHub Actions compiles and signs the APK for Android arm64.
- Existing saved movie archives rerun full word alignment once because v48's
  completion marker is different from the previous pass.
- Music, simultaneous speakers and ambiguous transcripts can still defeat
  VAD/CTC. No numerical increase in **genuine** word accuracy is guaranteed.
  Check accuracy by listening to the same selected speech lines on-device.
