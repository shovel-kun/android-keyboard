# English and Japanese OCR for archived images

Investigated 2026-09-18 against checkout `53c2367455` and current upstream sources.
Status: implemented after user approval below. The original investigation and proposed evaluation are retained for context; archive-quality/device benchmarks were explicitly skipped.

## Recommendation

Start a prototype with **PP-OCRv6_small_det + PP-OCRv6_small_rec**, using the official ONNX models and Paddle's Android SDK as the implementation reference. The small recognizer supports English and Japanese together. Do not choose the v6 tiny recognizer: Japanese is explicitly excluded. Medium is a possible quality comparison if small misses important text; Paddle's own benchmark is not evidence of accuracy on this archive. [Official v6 description](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/algorithm/PP-OCRv6/PP-OCRv6.en.md)

The official small ONNX detector is approximately 9.88 MB and recognizer 21.2 MB: **31.1 MB of model graphs**, plus recognition configuration/dictionary and dependencies. This is not an APK-size or RAM estimate. Both model repositories declare Apache-2.0. Pin exact revisions and hashes when implementing. [Detector files](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/tree/main), [recognizer files](https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/tree/main)

Paddle supplies an independently integrable Android SDK, through source or AAR, using ONNX Runtime and OpenCV. It supports v6 small and v5 mobile ONNX models and returns text, confidence and quadrilateral coordinates. This avoids starting with custom model conversion or implementing the entire OCR pipeline from scratch. [Android deployment](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/cross_platform/android_deployment.en.md)

For comparison, the official v5 mobile ONNX files are approximately 4.83 MB detection + 16.5 MB recognition = **21.3 MB**. Its recognizer also supports EN/JP; it is the smaller baseline to test rather than assuming the newest model is automatically best for the phone. [v5 detector](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/tree/main), [v5 recognizer](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/tree/main), [recognition model coverage](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/module_usage/text_recognition.en.md)

## Fit with this app

These are observations from the current checkout, not assumptions from the tagger's design:

| Existing integration point | OCR implication |
| --- | --- |
| `build.gradle:182` pins the tagger artifact and checksum; `:399` leaves ONNX assets uncompressed; `:463` already supplies ONNX Runtime Android 1.23.2 | Reuse the bundled, offline asset workflow and existing runtime. Measure additional OpenCV/native-library size. |
| `build.gradle:245` sets minSdk 24 | Upstream SDK sets minSdk 26. Audit and backport actual API requirements before integration; do not silently raise the keyboard's minimum. |
| `ClipboardImageTagger.kt` owns inference behind a small interface | Add a separate OCR interface that owns SDK setup, image handling, inference and result conversion. |
| `ClipboardImageTagCoordinator.kt` lazily opens a session for a serial queue and closes it after draining | Follow that lifecycle, but coordinate OCR and tagging so two heavy inference jobs do not compete while typing. |
| `ClipboardArchive.kt:71` stores per-media `imageTagging`; archive JSON ignores unknown keys | Add optional per-media OCR results with a default null value, separate from provider metadata and semantic tags. |
| `ClipboardHistoryManager.kt:1276` sometimes feeds tagging a thumbnail | Read original saved still images for OCR; thumbnails can discard small text. Bound decode dimensions and preserve coordinate transforms. |
| `ClipboardArchiveUi.kt:657` searches metadata and generated tag names; `ClipboardSearch.kt` handles explicit tag filters | Include successful OCR text in archive free-text matching without making recognized words into `tag:` suggestions. |
| `SettingsExporter.kt:338` and `:429` serialize archive records for backup | OCR can travel with those records, but import/reducer merge behavior and old backups need explicit validation. |

The SDK minimum is confirmed in its [Gradle source](https://github.com/PaddlePaddle/PaddleOCR/blob/main/deploy/ppocr-android/ppocr-sdk/build.gradle.kts). Its [public Kotlin API](https://github.com/PaddlePaddle/PaddleOCR/blob/main/deploy/ppocr-android/ppocr-sdk/src/main/java/com/paddle/ocr/PaddleOCR.kt) has an Apache-2.0 header and coroutine entry points. Align dependencies with this app, preserve notices, and verify the pinned SDK against the existing runtime rather than copying its whole demo project.

## Proposed behavior and data

The following is a proposed scope, not an approved implementation:

1. Add **Extract text** to an archived still image and show selectable text with **Copy text**. Run locally against the saved image.
2. Persist a result containing pipeline/model revision, input identity, attempt time, image dimensions, recognized regions (text, confidence, quadrilateral), and success/failure state. An empty successful result means “no text found,” not “not processed.”
3. Make successful OCR text searchable in Archives. Keep original text for display; derive a normalized search form for Latin case and Japanese full/half-width matching. Do not require Japanese words to be space-separated. Decide separately whether linked Clips should match it too.
4. Offer explicit batch extraction for existing images after device measurements. Record completed results so a later invocation can resume. Keep work off the input/UI thread, allow cancellation, and do not assume an in-memory coroutine queue survives process death.
5. Invalidate OCR when the image changes. Before applying queued results, match the current media identity so deletion, re-download or import cannot attach stale text to a replacement. Define merge precedence using both input identity and pipeline revision.

An initial version needs no translation, text generation, animated-frame processing, or full document reconstruction. Detection and recognition are separate models: detect regions, rectify/crop them, recognize each crop, decode text, then assemble display/search results. Reuse upstream preprocessing and decoding conventions, including the matching dictionary; an ONNX graph alone does not perform the full pipeline.

## Japanese quality is the decision gate

EN/JP language support does not establish manga usability. Treat the following as distinct evaluation cases: horizontal Japanese, true top-to-bottom columns with right-to-left column order, mixed EN/JP, furigana beside main text, speech bubbles, stylized sound effects, small screenshot text and low-contrast text. Rotating an image is not equivalent to solving vertical typesetting and reading order.

This is a concrete integration concern: upstream `QuadTextCrop` rotates tall crops (height/width >= 1.5) by 90 degrees counterclockwise, and `BoxSorter` uses top-to-bottom, then left-to-right ordering with a 10-pixel row tolerance. Inference: that ordering is not a Japanese right-to-left column policy. [Crop source](https://github.com/PaddlePaddle/PaddleOCR/blob/main/deploy/ppocr-android/ppocr-sdk/src/main/java/com/paddle/ocr/postprocess/QuadTextCrop.kt), [sort source](https://github.com/PaddlePaddle/PaddleOCR/blob/main/deploy/ppocr-android/ppocr-sdk/src/main/java/com/paddle/ocr/postprocess/BoxSorter.kt)

Keep region coordinates even if the first UI only displays text. They make it possible to improve ordering and identify mistakes later without rerunning detection. Search may remain useful when paragraph order is wrong, but Copy text quality will not; measure those outcomes separately.

## Small evaluation before feature work

- Build a reproducible local harness with pinned models, dictionary, SDK revision and preprocessing. Compare v6 small with v5 mobile; add medium only if quality failures justify its cost.
- Use a proposed 60–100 manually transcribed, representative images, balanced across EN, horizontal JP, vertical JP/manga, mixed text, and images without text. Keep selection/licensing documented. No archive sample set was accessed during this investigation.
- Report detection misses, character error rate per language/layout, search-query recall, spurious text on no-text images, and reading-order failures. Inspect furigana separately rather than silently treating it as noise.
- Run cold and warm inference on the target ARM64 phone: median/p95 latency, peak memory, APK increase, batch thermal behavior and typing responsiveness. Include long/tall images and dense pages. Desktop timing cannot establish these.
- Verify API 24 compatibility before adopting the SDK. Preserve a float model baseline before trying quantization; compare the same EN/JP sample set after any conversion.
- When implementing persistence, focus tests on old/new backup round trips, import merge, stale result rejection, empty successes, cancellation and searchable Japanese text. UI previews and device interaction checks come with the actual UI work.

Google's bundled ML Kit Japanese recognizer is a possible Android baseline if Paddle integration or accuracy disappoints; it has an official on-device Android API and a bundled installation path. It does not reuse this project's ONNX pipeline, so it is not the first choice here. [ML Kit Android guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)

**Decision:** Paddle is a good fit to prototype. Prefer v6 small, retain v5 mobile as a comparison, and make vertical-Japanese quality and phone resource usage the adoption gates. No accuracy or performance outcome has been measured yet.

## Implementation authorization (2026-09-18)

The user approved direct implementation without the proposed evaluation set, and explicitly authorized Android 13 (API 33) as the new minimum. The evaluation and API-24 gates above are superseded by that instruction. Implement per-image extraction, persisted selectable/copyable text, archive free-text search and explicit resumable batch extraction, with no runtime model downloads. Model quality remains unmeasured.

Implementation and validation details: [Paddle OCR module](../paddle-ocr/README.md). The Android minimum is now 33; models are bundled and checksummed. No evaluation dataset or quality benchmark was added.
