# Plan 001: Add private on-device AI tags to clipboard archives

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report; do not improvise. When done, update the status row for this plan in
> `plans/README.md` unless a reviewer told you they maintain the index.
>
> **Drift check (run first)**:
> `git diff --stat 51455e6e6..HEAD -- build.gradle java/AndroidManifest.xml java/assets java/res/values/strings-uix.xml java/src/org/futo/inputmethod/latin/uix/actions/clipboard java/src/org/futo/inputmethod/latin/uix/settings/pages/credits src/test/java/org/futo/inputmethod/latin/uix/actions/clipboard`
> If an in-scope file changed since this plan was written, compare the current
> state excerpts below against live code. A mismatch in archive media identity,
> archive persistence, search, or download completion is a STOP condition.

## Status

- **Outcome**: DONE on 2026-07-26 with the operator-approved simplified
  personal-build UX. The bundled model, automatic tagging for newly archived
  media, explicit existing-image backfill, tag search/details, retry, settings
  toggle, persistence, and tests are implemented. Clear-tags/model-management
  UI and extensive disclosure copy were intentionally omitted. Physical-device
  timing is still unmeasured because no Android device was connected.
- **Priority**: P1
- **Effort**: L (multi-day, including a physical-device benchmark)
- **Risk**: HIGH (large native ML runtime inside an input-method app)
- **Depends on**: none
- **Category**: direction
- **Planned at**: commit `51455e6e6`, 2026-07-26

## Why this matters

Clipboard archives already retain local images and expose a combined search box,
but provider tags only exist when the remote provider supplies them. Local image
classification can make otherwise textless archives searchable by visual terms
such as `blue hair`, `cat`, or a character name. It must preserve the keyboard's
privacy promise: image bytes never leave the device and inference stays local.

The chosen model has real costs. The mobile ONNX file is 110,600,268 bytes,
requires ONNX Runtime Extensions for embedded image preprocessing, and was
trained on Danbooru rather than general photography. This plan therefore gates
the full feature on measured Android performance and makes those limitations
visible in settings. APK size is not a product constraint for this personal,
sideloaded build; package the exact pinned model instead of downloading it at
runtime.

## Product and UX contract

### Recommended user flow

1. Clipboard settings show **AI image tagging (on-device)**, enabled by default
   for this personal build. Its subtitle says that images stay on-device, the
   bundled vocabulary is optimized for anime/illustration, and generated labels
   can include sensitive terms. The switch can stop future inference without
   deleting existing tags.
2. Every newly saved archive media item is queued for tagging. There is no model
   setup, download, network permission flow, or first-run modal: the exact model
   is part of the APK and works in airplane mode.
3. Inference is single-concurrency and never blocks archive download completion,
   saving, rendering, or keyboard input.
4. Existing archives are not silently backfilled. Settings show the eligible
   count and a **Tag existing archives** action. Once requested, backfill is
   resumable after process death and shows `Tagging images - N remaining` on the
   archive screen.
5. The existing archive search field matches generated tags. Normalize spaces
   and underscores so `blue hair` matches the canonical `blue_hair` label.
6. Archive details identify **AI-generated tags** separately from provider tags,
   grouped per media item with rounded confidence percentages and model name.
   Do not cover archive cards in dozens of chips.
7. A gallery action **Tag image** or **Retry image tagging** handles one item on
   demand.
8. A confirmed **Clear generated tags** action turns automatic tagging off,
   clears the resumable backfill request, and removes tag results from archive
   metadata. There is no model-removal control because the model is an immutable
   APK asset.

### Deliberate v1 limits

- Store and search general tags at probability >= 0.35 and character tags at
  probability >= 0.80, matching WaifuTagger's conservative UI defaults.
- Persist at most 64 general and 16 character tags per media item, sorted by
  descending probability. Discard rating-category outputs in v1; do not expose
  `general`, `sensitive`, `questionable`, or `explicit` as search tags.
- Feed original bytes for JPEG and PNG. For other saved image formats, GIFs, or
  videos, feed the existing JPEG sidecar thumbnail when present. This gives
  animated/video archives representative-frame tags without adding another
  decoder pipeline. If neither source is a readable JPEG/PNG, record a small
  typed `UnsupportedInput` result rather than altering archive media status.
- Generated-tag failure must never change `ClipboardArchiveMediaStatus` or
  `ClipboardLinkArchive.status`; download health and optional AI enrichment are
  separate state machines.
- No cloud inference, manual tag editing, rating filter, model auto-update, or
  confidence sliders in this plan.

## Reference material and pinned artifacts

WaifuTagger was inspected from a shallow clone at commit
`9517c70139fd8caa46b6ed1fa61996d17354e3b7`. Use it as a behavioral reference,
not a source-code dependency:

- `src/hooks/useModel.ts` loads `model.quant.preproc.onnx`, sends encoded image
  bytes as a UINT8 tensor named `image`, runs one session, and parses the first
  output.
- `src/parse.ts` separates category 0 general, category 4 character, and category
  9 rating labels.
- `src/store/settings.ts` defaults to 0.35 general and 0.80 character thresholds.
- The clone has no `LICENSE` file. Do not copy its TypeScript, components, or
  `assets/tags.json` into this repository.

Pin these model artifacts; do not use mutable `main` URLs or `latest.release`:

| Artifact | Pinned source | SHA-256 / size |
|---|---|---|
| Mobile model | `https://huggingface.co/Smashinfries/wd-convnext-tagger-v3-mobile/resolve/b835f21a156d9879ed38fa5d0b5e822bb0c58739/model.quant.preproc.onnx` | `e504d4ed9499f58249ba6bafb5d862565e3725cb08f0133874b8a8c5a68c02a0`, 110,600,268 bytes |
| Label table | `https://huggingface.co/SmilingWolf/wd-convnext-tagger-v3/resolve/d39e46de298d27340111b64965e20b8185c407e6/selected_tags.csv` | `298633d94d0031d2081c0893f29c82eab7f0df00b08483ba8f29d1e979441217`, 308,468 bytes |

Both model cards declare Apache-2.0. The label table contains 10,861 rows: 8,106
general, 2,751 character, and 4 rating labels. The model input preprocessing
resizes/letterboxes to 448x448 and is embedded with ONNX Runtime Extensions.
The upstream model card says the base ONNX model requires ONNX Runtime >= 1.17.

Use pinned Gradle dependencies for the spike:

- `com.microsoft.onnxruntime:onnxruntime-android:1.23.2`
- `com.microsoft.onnxruntime:onnxruntime-extensions-android:0.13.0`

If this pair cannot create a session with the pinned model after registering the
Extensions custom-op library, stop. Do not switch versions randomly.

## Current state

- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardArchive.kt:44-67`
  owns serializable per-media and archive metadata. `ClipboardArchiveMedia`
  currently ends at `thumbnailUrl`; generated tags belong here, not in a second
  gallery database.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardArchiveStore.kt:120-129`
  atomically saves one archive metadata JSON and verifies the decoded result.
  Tag results must use this same save path and therefore automatically join
  clipboard backup/import.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardArchiveProjection.kt:15-52`
  creates immutable UI snapshots from archive metadata and known media files.
  Expose tag progress/results through this projection rather than allowing
  Composables to inspect model/storage internals.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardHistoryManager.kt:301-365`
  is a process-wide singleton with a process-lifetime coroutine scope, archive
  download coordinator, archive map, and debounced save queue.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardHistoryManager.kt:2015-2066`
  is the real saved-media seam: `MediaDownloadSaved` is reduced, entry previews
  are updated, and the archive is queued for persistence. Enqueue new-media
  tagging only after that durable archive transition.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardHistoryScreen.kt:83-91`
  owns query/filter UI state. Lines 193-211 derive visible clips and archives.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardArchiveUi.kt:611-630`
  already searches provider, URL, title/body/author, and provider
  `metadata.tags`. Extend this pure function with completed AI tag names.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardHistoryScreen.kt:482-520`
  already renders the archive search field and filter affordance.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardArchiveComponents.kt:1200-1348`
  owns gallery actions and details routing. Lines 1381 onward render archive
  metadata sections.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardHistoryAction.kt:45-140`
  is the canonical home for clipboard settings keys; lines 204-440 compose the
  clipboard settings page.
- `java/src/org/futo/inputmethod/latin/uix/settings/pages/credits/ThirdPartyList.kt`
  is the existing third-party attribution list and already supports Apache-2.0
  and MIT license text.
- `ClipboardUtil.thumbnailFor(file)` resolves the existing JPEG sidecar.
  `ClipboardUtil.generateThumbnail(file)` already handles static images, GIFs,
  and videos; do not create a parallel thumbnail format.

### Archive invariants to preserve

- Provider-backed complete archives with saved media are immutable snapshots
  against later remote manifest rewrites. Local tag enrichment may update its
  own per-media field, but must not allow a later manifest to replace saved
  media or erase tags.
- Tag-only mutations must preserve archive `createdAtEpochMs` and
  `updatedAtEpochMs`; otherwise a one-time backfill reorders the entire
  **Last updated** gallery. Store `attemptedAtEpochMs` in tag state instead.
- Imported archives can carry completed generated tags even when the model is
  from another revision. They remain searchable. Backup/import carries tag
  metadata, not the bundled model asset.
- Deleting an archive deletes its tag metadata with the archive. Removing a
  media item removes that media item's tags through existing identity/reducer
  semantics.

## Commands you will need

Run Gradle commands sequentially; do not run multiple Gradle tasks concurrently.

| Purpose | Command | Expected on success |
|---|---|---|
| Prepare model | `./gradlew prepareImageTaggerModel --stacktrace` | the pinned model is downloaded or verified |
| Verify model | `sha256sum java/assets/image-tagger/model.quant.preproc.onnx` | `e504d4ed9499f58249ba6bafb5d862565e3725cb08f0133874b8a8c5a68c02a0` |
| Fetch labels | `curl -fL 'https://huggingface.co/SmilingWolf/wd-convnext-tagger-v3/resolve/d39e46de298d27340111b64965e20b8185c407e6/selected_tags.csv' -o java/assets/image-tagger/selected_tags.csv` | exit 0 |
| Verify labels | `sha256sum java/assets/image-tagger/selected_tags.csv` | `298633d94d0031d2081c0893f29c82eab7f0df00b08483ba8f29d1e979441217` |
| Focused tests | `./gradlew testStableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.clipboard.*' --stacktrace` | all clipboard tests pass |
| Debug APK | `./gradlew assembleUnstableDebug --stacktrace` | exit 0 and unstable debug APK exists |
| Scope check | `git diff --check` | no output, exit 0 |

## Scope

**In scope** (the only production areas to modify):

- `.gitignore` - keep the downloaded ONNX build input out of Git history.
- `build.gradle` - pin ONNX Android and Extensions dependencies, download and
  verify the exact model before builds, and package `.onnx` uncompressed.
- `java/assets/image-tagger/model.quant.preproc.onnx` - ignored local build input
  downloaded from the pinned Apache-2.0 model revision and bundled into the APK.
- `java/assets/image-tagger/selected_tags.csv` - pinned Apache-2.0 label order.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardArchive.kt`
  - serializable tag result and reducer events.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardImageTagger.kt`
  (new) - engine interface, ONNX implementation, label parsing and result policy.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardImageTagCoordinator.kt`
  (new) - single-concurrency queue and resumable backfill projection.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardHistoryManager.kt`
  - orchestration, save/retry/clear entry points.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardArchiveProjection.kt`
  - immutable tagging state exposed to UI.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardArchiveUi.kt`
  - pure tag search and details presentation.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardArchiveComponents.kt`
  and `ClipboardHistoryScreen.kt` - progress, detail rows, manual tag action.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardImageTaggingComponents.kt`
  (new) - settings/control UI if keeping it separate makes the existing 1,625-line
  component file smaller.
- `java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardHistoryAction.kt`
  - settings keys and settings-page placement.
- `java/res/values/strings-uix.xml` - all user-facing copy.
- `java/src/org/futo/inputmethod/latin/uix/settings/pages/credits/ThirdPartyList.kt`
  - model, ONNX Runtime, and Extensions attribution.
- Clipboard unit tests under
  `src/test/java/org/futo/inputmethod/latin/uix/actions/clipboard/`, including new
  engine-policy/coordinator tests.
- One debug-only instrumented or developer harness for the physical-device
  benchmark if no existing harness can call the engine safely.

**Out of scope**:

- `ClipboardPreviewMetadata.tags`; provider tags keep their current meaning.
- Link-preview fetch/parsing behavior and archive download limits.
- Cloud endpoints, user accounts, analytics, or uploading any image bytes.
- Runtime model download, model removal, mutable model URLs, or model updates.
- Copying the bundled model into app-private storage or clipboard backup.
- WorkManager/foreground-service infrastructure unless the benchmark gate fails
  and a human explicitly approves a follow-up architecture plan.
- Changing archive media status, archive status, color detection, provider
  filters, or automatic pruning.
- Manual tag editing, model selection, rating tags, confidence sliders, NNAPI,
  GPU delegates, custom ONNX Runtime builds, or model-update infrastructure.

## Git workflow

- Branch: `feat/clipboard-ai-image-tags`
- Follow current history: one logical conventional commit, e.g.
  `feat(clipboard): tag archived images on device`, or split the benchmark
  harness from the feature if the first commit is independently useful.
- Keep the downloaded model ignored. Commit its pinned URL/hash preparation task
  and the label table, but not build outputs, device measurements, or temporary
  files.
- Do not push or open a pull request unless the operator asks.

## Steps

### Step 1: Prove the pinned model on a physical Android device

Add the pinned ONNX dependencies, the pinned label CSV, and the smallest
`ClipboardImageTagger` implementation needed to:

- register ONNX Runtime Extensions custom operators;
- open the bundled, uncompressed model asset with `AssetManager.openFd()`, map
  its exact offset/length read-only through `FileChannel.map`, and pass the
  resulting direct `ByteBuffer` to `OrtEnvironment.createSession(ByteBuffer,
  SessionOptions)` without copying the full model into heap or private storage;
- feed encoded JPEG bytes as a UINT8 tensor with the model's actual input name;
- read the first float output and assert its length equals the 10,861 labels;
- map category 0/4 only, apply the fixed thresholds/caps, and close input tensor,
  session result, session options/session, and environment-owned values at their
  documented lifecycle boundaries.

Do not catch exceptions inside parsing or inference loops. Translate exceptions
once at the engine boundary into the small typed failure set.

On one representative arm64 physical device, measure:

- APK size delta from the bundled model and two AAR dependencies;
- installed-app size and whether the ONNX asset remains mmap-backed rather than
  duplicated in private files;
- cold session creation plus first inference;
- median of five warm inferences using archive images;
- process PSS before, during peak inference, and after the session is closed;
- whether the keyboard process survives and accepts normal typing immediately
  after a batch.

Record the device model/Android version and numbers in the eventual commit or PR
description, not in source code.

**Gate**: continue only if session creation works with the pinned dependency
pair, output length is 10,861, warm median is <= 5 seconds/image, peak PSS is <=
350 MiB, memory substantially drops after session close, and no ANR/process death
occurs. These are generous safety ceilings, not performance targets.

**Verify**: install the unstable debug APK and run the harness against at least
five real archived images; all gate conditions above hold.

### Step 2: Add a versioned, bounded per-media tagging contract

In `ClipboardArchive.kt`, add serializable types equivalent to:

- `ClipboardImageTagCategory { General, Character }`
- `ClipboardImageTag(name, probability, category)`
- `ClipboardImageTaggingStatus { Complete, Failed }`
- `ClipboardImageTaggingFailure { UnsupportedInput, DecodeFailed, InferenceFailed }`
- `ClipboardImageTaggingResult(modelRevision, status, attemptedAtEpochMs, tags,
  failure)`
- `ClipboardArchiveMedia.imageTagging: ClipboardImageTaggingResult? = null`

Use a stable revision string that includes the model commit/SHA and policy
revision. A completed empty tag list is a valid completed result. A failed result
prevents automatic retry loops; retry it only on explicit user action or a new
model revision.

Add reducer events for successful and failed tagging keyed by stable media
identity (`sourceUrl` plus existing archive media identity rules). Preserve the
archive timestamps for tag-only events. Ensure manifest merge, import, disk
reconciliation, and normalization preserve valid tags for unchanged media.
Normalize imported results at the decode/reducer boundary: known categories,
finite probabilities in 0..1, nonblank names of bounded length, fixed per-category
caps, and deterministic descending order.

**Verify**: focused reducer/backup tests round-trip completed/failed/empty tags,
preserve them across a manifest refresh and import, drop them with deleted media,
and prove tag-only events do not change archive status or timestamps.

### Step 3: Package and verify the immutable model assets

Ignore `java/assets/image-tagger/model.quant.preproc.onnx`, retain the label table,
and add a Gradle preparation task that downloads the model from the immutable
pinned URL and validates its SHA-256. Wire that task before Android packaging so
the model remains an untracked local build input rather than a repository object.

Extend the existing Android `aaptOptions` block to keep both `dict` and `onnx`
uncompressed. The Gradle preparation task must reject a partial download or a
different revision before it can enter the APK. Keep the label verification in
the existing asset checks. Do not add runtime downloads, mirrors, mutable URLs,
or a fallback that copies the model into private storage.

The engine should open the uncompressed asset descriptor and create the ONNX
session from its mapped `ByteBuffer`. Verify on Android that closing the file
channel/descriptor after session construction is safe; otherwise keep the
minimal owning resource alive for exactly the session lifetime. A compressed
asset or unavailable descriptor is a packaging defect, not a condition to hide
behind a second storage path.

Add third-party credits for WD ConvNext Tagger v3 / mobile quantization
(Apache-2.0), ONNX Runtime (MIT), and ONNX Runtime Extensions (MIT). Do not list
WaifuTagger as copied code; it was only a reference.

**Verify**: the Gradle task downloads a missing model, is up to date when its hash
matches, rejects a wrong hash, and APK inspection shows one uncompressed ONNX
entry with the exact byte size.

### Step 4: Add single-concurrency tagging orchestration

Create a coordinator owned by `ClipboardHistoryManager`, matching the existing
archive download coordinator pattern. It must:

- use one inference lane (`Dispatchers.Default.limitedParallelism(1)`);
- create one session for a nonempty batch and close it when the queue drains;
- resolve original JPEG/PNG bytes or the existing JPEG sidecar fallback;
- never mutate archive maps off the main dispatcher;
- reduce a tag event into the current archive and use `queueArchiveSave`;
- expose immutable active key, completed count, remaining count, and last typed
  failure through `archiveUiSnapshot()`;
- deduplicate queue identity by archive key plus media identity;
- enqueue newly saved media immediately after `MediaDownloadSaved` is reduced;
- resume only a user-requested existing-archive backfill after manager load;
- skip completed current-revision and failed current-revision results during
  automatic scans;
- support tag-one/retry-one, request/cancel backfill, and clear all generated
  tags.

Do not make archive download wait for inference. Do not write AI tags into
`ClipboardEntry`; archives are their owner and search already operates on the
archive snapshot.

**Verify**: coordinator tests with a fake tagger prove FIFO single concurrency,
deduplication, process-resume selection, success/failure persistence calls,
manual retry, cancellation, and no queueing while disabled.

### Step 5: Extend archive search and details without clutter

Update `matchesArchiveQuery` so completed current or imported generated tag names
join the haystack. Normalize lowercase plus underscore/space equivalence. Search
depends only on persisted metadata, not a live inference session.

Add generated-tag sections to archive metadata details, grouped by media index.
Show humanized names (`long_hair` -> `long hair`), category, rounded confidence,
model display name, and failed state without raw exception text. Add a gallery
FAB action for tag/retry on the selected media.

Add a small backfill progress surface near the existing archive backfill status.
Do not add tag chips to every archive card in v1 and do not fold AI failure into
the archive's existing attention/download badge.

**Verify**: UI-presentation tests prove searches for canonical and spaced names,
provider tags still work, rating outputs never match, imported tags from another
revision work, details distinguish provider vs AI tags, and AI failures do not
affect archive filters/status.

### Step 6: Add honest controls and destructive confirmation

Add settings keys for automatic tagging and resumable backfill request next to
the existing clipboard settings keys. Default automatic tagging to enabled for
this personal build. Build the compact control component described in the UX
contract: switch, plain-language bundled-model disclosure, eligible existing
count/backfill action and progress, and clear-tags action. Use string resources
for every visible label.

Clearing generated tags requires confirmation, turns tagging/backfill off first,
updates each affected archive through the manager/reducer, and flushes archive
saves before reporting completion. Re-enabling later tags only newly saved media;
the user must explicitly request another existing-archive pass.

Add Compose previews for ready/idle and backfilling states if the component is
reasonably isolated. Visually verify them at phone width before calling the step
done.

**Verify**: `./gradlew assembleUnstableDebug --stacktrace` exits 0, previews render,
and a manual device pass completes every flow listed in the UX contract.

### Step 7: Run regression and release-size verification

Run the full focused clipboard suite, then build unstable debug. Compare APK size
to the existing baseline and document the bundled-model/runtime delta. Inspect
the APK to prove `model.quant.preproc.onnx` is present exactly once, stored rather
than deflated, and is exactly 110,600,268 bytes; also confirm there is no copy in
the app-private files directory after inference.

Manually verify:

- no image request is made during inference (network logging or airplane mode);
- new archive images become searchable after tagging;
- existing backfill resumes after force-stopping/relaunching the app;
- a search such as `blue hair` finds a stored `blue_hair` result;
- turning the feature off stops new inference while old tags still search;
- clear-tags disables future inference, removes generated metadata, and search
  no longer matches until the feature/backfill is explicitly enabled again;
- archive download status, retry, color filter, backup/export/import, deletion,
  and keyboard typing still behave normally.

**Verify**: all commands in "Commands you will need" pass sequentially; APK
inspection finds the one exact uncompressed model asset; Git contains no model
object; `git status --short` contains only in-scope source/assets/tests plus the
executor's `plans/README.md` status update.

## Test plan

- New `ClipboardImageTaggerTest.kt`: label order/count, category split,
  thresholds, caps, deterministic sorting, NaN/out-of-range normalization,
  rating exclusion, underscore display/search normalization.
- New `ClipboardImageTagCoordinatorTest.kt`: fake-engine orchestration cases from
  Step 4. Model after `ClipboardArchiveDownloadCoordinatorTest.kt`.
- `ClipboardArchiveReducerTest.kt`: tag events, timestamp/status invariants,
  manifest/import preservation, media deletion, failed/empty result behavior.
- `ClipboardArchiveUiTest.kt`: generated-tag query behavior and detail sections;
  model after current provider/status/color and metadata-details tests.
- `ClipboardBackupTest.kt`: archive metadata JSON and staged import round-trip
  generated tags; the model binary itself must never appear in backup inventory.
- Gradle asset verification: pinned model download and exact model size/SHA plus
  label SHA before packaging.
- One physical-device smoke/benchmark pass is mandatory because local JVM tests
  cannot validate ONNX custom operators, native ABI packaging, PSS, or keyboard
  responsiveness.

## Done criteria

- [x] Automatic tagging defaults on, can be disabled, and performs inference
      locally without a network path.
- [x] The exact pinned model is downloaded with SHA verification and present once
      as a 110,600,268-byte uncompressed APK asset loaded through `openFd`/memory
      mapping.
- [x] New saved media tags automatically; existing media tags only after the
      explicit backfill action or per-image action.
- [x] Tag inference is single-concurrency, off-main, session-scoped to a batch,
      and cannot change archive download/status state.
- [x] Generated tags persist per media with confidence, model revision, bounded
      size, and backward-compatible serialization defaults.
- [x] Archive timestamps/sort order do not change solely because tags were added.
- [x] Existing archive search matches generated tags with underscore/space
      normalization without requiring a live inference session.
- [x] Rating-category outputs are neither persisted as search tags nor shown.
- [x] Third-party model/runtime attribution is present.
- [x] `./gradlew testStableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.clipboard.*' --stacktrace` passes.
- [x] `./gradlew assembleUnstableDebug --stacktrace` passes.
- [x] `git diff --check` passes and only scoped files changed.
- [x] `plans/README.md` marks Plan 001 DONE.
- [ ] Follow-up: measure cold/warm inference and process memory on a physical
      Android device; none was connected during implementation.

## STOP conditions

Stop and report rather than improvising if:

- Archive ownership or the `MediaDownloadSaved` -> reducer -> save seam differs
  from the current-state excerpts after the drift check.
- The pinned ONNX Runtime/Extensions pair cannot load the pinned model, output is
  not exactly 10,861 floats, or label order cannot be proven identical.
- The physical-device benchmark exceeds any Step 1 safety ceiling or causes an
  ANR, process death, sustained memory after session close, or noticeable
  keyboard-input disruption. The likely next design is an isolated process, but
  that requires a separate approved plan.
- Legal review rejects direct download/use of the Apache-2.0 model or label data.
- The exact model cannot be downloaded and verified from its pinned source, or
  the Android build cannot expose the packaged asset uncompressed through
  `openFd` for mapped loading.
- Supporting the model requires a mutable runtime URL, uploading user images,
  copying the full asset into private storage, or copying unlicensed WaifuTagger
  source/assets.
- Correct persistence requires mixing generated tags into provider metadata or
  changing archive media/download status semantics.
- A verification step fails twice after a reasonable focused correction.
- Implementation requires touching an out-of-scope subsystem.

## Maintenance notes

- Treat `modelRevision` as model weights + label order + selection policy. Any of
  those changing requires a new revision and explicit re-tag UX.
- Reviewers should scrutinize resource closure, main-thread boundaries, queue
  deduplication, exact hash pinning, and manifest merges that might erase tags.
- The model is Danbooru-trained and not a general photo classifier. Poor results
  on ordinary photos are a product limitation, not necessarily an inference bug.
- A future isolated-process plan is justified only by measured PSS/latency; do
  not preemptively add Binder/WorkManager complexity.
- A future model update should be user-initiated and side-by-side verified before
  replacing the pinned file. Never silently invalidate or erase old searchable
  tags.
