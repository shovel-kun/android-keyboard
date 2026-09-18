# Paddle OCR Android engine

Vendored SDK source from [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR/tree/dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf/deploy/ppocr-android/ppocr-sdk), commit `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf` (Apache-2.0, see LICENSE and source headers). Demo, benchmarks, model assets and the unused coroutine facade/result wrapper are omitted.

The local Gradle module uses this app's ONNX Runtime 1.23.2, official OpenCV 4.12.0 (instead of the older QuickBird distribution), and minimum Android 13. The engine source is otherwise upstream. Swipe and OpenCV both bundle libc++; app packaging selects one copy. Their exported symbols were checked to match for all four ABIs. Models are bundled by the app, pinned by revision and SHA-256 in `java/assets/image-ocr/models.json`; `prepareOcrModels` downloads and verifies missing assets at build time. Both official PP-OCRv6 small ONNX model repositories declare Apache-2.0.

The app's `ClipboardOcr` interface isolates this engine from persistence/UI. Change the engine/model assets and bump `ClipboardOcrModelRevision` when replacing models or preprocessing. Existing results stay readable; batch extraction processes results from older revisions again.

OCR uses saved still images, software decoding with orientation applied, a 2560-pixel maximum side, two inference threads and a 0.5 recognition-confidence floor. Stored coordinates refer to the decoded image dimensions. Work is serialized with image tagging. Tall crops and reading order follow upstream; manga/vertical Japanese quality has not been evaluated.

Validation (2026-09-18): 116 targeted archive reducer, search, backup and OCR-cancellation tests passed; `assembleUnstableDebug` passed. The packaged model/config hashes were verified, and both models ran synthetic inputs using desktop ONNX Runtime 1.23.2; dictionary/output dimensions matched. This is a compatibility smoke check, not a quality benchmark or Android-device inference test.

Both new OCR dialog previews were rendered and visually inspected (bilingual result and failure at 1.5× font scale). The aggregate preview task failed on other existing previews; the two OCR PNGs were produced successfully. Temporary preview-plugin configuration was removed after inspection.
