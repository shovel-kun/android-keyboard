# Plan 002: Add local tag suggestions to clipboard search

Status: DONE. Priority: P1. Effort: M. Risk: medium (search semantics and IME editing).
Planned at commit `13af9e2bce`, 2026-09-17. Depends on plan 001 (done).

## Intent and proposed product decisions

Add booru-style autocomplete to the Clipboard History app (Clips and Archives)
and keyboard clipboard menu using persisted local image-tagger results. This plan was implemented on 2026-09-17.
Keep ordinary text search compatible and retain the recent scroll fixes.

- Suggest only General and Character tags occurring in the current searchable
  collection. Do not suggest the whole model vocabulary or provider tags.
- Each suggestion shows its canonical underscore name, category label and number
  of matching cards. Category color is supplementary, never the only cue.
- Selecting `blue_hair` inserts `tag:blue_hair ` into search. Explicit tag syntax
  avoids silently changing ordinary words into filters as new images get tagged.
- `tag:blue_hair tag:solo` requires both tags; `-tag:hat` excludes a tag. No OR,
  wildcard syntax, aliases, inferred implications, or typo correction in v1.
- Remaining free text uses the existing surface's substring search semantics.
  Remove recognized tag spans, trim/collapse their separating whitespace, then
  pass the remaining phrase to the existing matcher. Plain text-only queries
  must stay unchanged. Tags match exactly after case normalization.
- Accept `tag:"blue hair"` as equivalent to `tag:blue_hair`; suggestions always
  insert underscore names. Only recognize operators at token boundaries, never
  within a URL or an arbitrary word. Incomplete `tag:` and unclosed quotes are
  editing states, not exceptions; show suggestions and do not apply the incomplete
  tag clause until it has a value. A complete unknown tag yields zero results.
- Evaluate tags at card level: an archive's tag set is the union of successful
  retained media tag results; linked clips inherit this set. Thus two tags may
  occur on different images in one album. A negative tag excludes the entire
  card if any retained image has it. Same-image search is explicitly deferred.
- Untagged clips remain available to normal search and negative-only filters;
  they cannot satisfy positive tag filters. No results are inferred from model
  probabilities discarded during tagging.

## Current state and ownership

Project root: `/home/user/Projects/kurumi/android-keyboard`. Kotlin/Compose Android
IME with native EditText wrappers; unit tests are JUnit, not Compose UI tests.
Use existing Kotlin formatting and avoid defensive layers or exception swallowing.

Production files below are in `java/src/org/futo/inputmethod/latin/uix/`:

- `actions/clipboard/ClipboardArchive.kt`: `ClipboardArchiveMedia.imageTagging`
  stores `ClipboardImageTaggingResult(modelRevision, attemptedAtEpochMs, tags,
  failure)`. Each tag has `name`, `probability`, and General/Character category.
- `actions/clipboard/ClipboardImageTagger.kt`: bundled WD model; labels in
  `java/assets/image-tagger/selected_tags.csv` contain 10,861 outputs, including
  rating labels that are not persisted as searchable tags. General/Character
  thresholds and caps are already applied before persistence.
- `actions/clipboard/ClipboardArchiveUi.kt`: `matchesArchiveQuery` currently
  searches one normalized substring across metadata and AI tags. It normalizes
  underscores to spaces. It does not implement tag conjunction/exclusion.
- `actions/clipboard/ClipboardHistoryModels.kt`: `matchesNormalizedQuery` searches
  clip text/provider metadata, not archived image-tagger results. Stable
  `lazyListKey()` now follows deduplicated entry identity.
- `actions/clipboard/ClipboardHistoryManager.kt`: owns reactive `linkArchives`;
  private `archiveForEntry` uses `entry.previewMetadata?.archiveKey()`. Existing
  archive resolution code also builds `archiveKeyByEntryKey`; inspect and reuse
  that mapping logic rather than reparsing every URL on each keystroke.
- `actions/clipboard/ClipboardArchiveProjection.kt`: archive UI snapshot and
  separate progress/activity snapshot. Search indexing must not depend on progress.
- `actions/clipboard/ClipboardHistoryScreen.kt`: 150 ms debounced search, separate
  grid states and gesture-based header visibility; preserve these behaviors.
- `actions/clipboard/ClipboardHistoryAction.kt` and `ClipboardHistoryActionWindow.kt`:
  toolbar editor and result grid have separate composable ownership. Lift only
  the shared search/editor state needed for the suggestion tray.
- `ActionTextEdit.kt`: `ActionEditText`, `GenericEditTextCompose`, `SettingsTextEdit`,
  `ActionTextEditor`, `ActionHeaderSearch` currently expose String state, not cursor
  selection. `ActionTextEditor` overrides the keyboard's input connection. Preserve it.

Reference behavior: Danbooru documents discovering canonical tags through
[autocomplete](https://safebooru.donmai.us/wiki_pages/howto%3Aget_started).
This plan adopts the interaction, not its full query language or remote service.

## Implementation sequence

### 1. Own query parsing and completion as pure Kotlin

Add `actions/clipboard/ClipboardSearch.kt` with a small parsed query model:
remaining text, included tags, excluded tags, and source spans. Include pure
functions for finding the active token from text + selection and applying a
suggestion as a text/selection replacement. Do not build a generic query framework.

Complete the token under the caret, not necessarily the last token. Preserve its
negative operator, surrounding query, and trailing text; append one space only
where needed. With a selection confined to one token, replace that token; hide
suggestions for selections spanning multiple tokens. Do not guess that multiple
unprefixed words form one tag: `blue_h` completes naturally, while spaced tag
names use the quoted form. Drop duplicate suggestions already used in either polarity.

Add parser/completion tests to a canonical new `ClipboardSearchTest.kt` in
`src/test/java/org/futo/inputmethod/latin/uix/actions/clipboard/`. This module is a
new unit ownership boundary. Model fixtures/assertions after ClipboardArchiveUiTest.
Verify with `./gradlew testUnstableDebugUnitTest --tests '*ClipboardSearchTest'`;
all cases below must pass before integration.

### 2. Build a shared immutable local tag index

In the same search module, build tag-to-card memberships and per-card tag sets
from immutable snapshots of archives plus entry-to-archive associations. Count a
card once per tag even if several images carry it. Exclude failed tagging results
and media removed from the archive; retain valid persisted tags when files are
missing, matching archive metadata search's offline behavior. Deleting an archive
must remove its suggestions. Clips never gain tags by unrelated filename matching.

Expose the index through the manager's existing state/projection pattern. Keep
only minimal identities and tag/category data as rebuild inputs. Build on
Dispatchers.Default using cancellation/versioning so a stale build cannot replace
a newer one. Do not rebuild for scroll, pin state, confidence-only changes,
download byte counters or keystrokes. Rebuild when tags, membership, deletion or
import state changes. No model invocation or file I/O during suggestions.

Rank matching names by exact match, prefix, then word-boundary prefix within an
underscore-delimited name; break ties by local count then canonical name. Normalize
once. A sorted list scan over observed unique tags is the initial implementation;
no trie, database migration or third-party dependency is needed initially.

Counts and candidates reflect the active surface and its non-text filters plus
completed tag clauses and completed free text, excluding the active completion
span. Show the number of cards carrying the proposed tag in that candidate set;
for a negative suggestion this is how many cards would be excluded, and label it
accordingly. Hide zero-count candidates. Compute at most eight displayed rows.
Suggestions follow raw editing state, separately from debounced result filtering.

Verify `ClipboardSearchTest`: fixtures covering multiple media, linked clips,
archive-only records, duplicate tags, failed results, deletion, empty library,
category distinctions, scope counts, ranking and stale-result suppression.

### 3. Extend the editor at the native editing seam

Add opt-in selection reporting and an atomic completion edit path in ActionTextEdit.kt.
Use native editable replacement + batch edit + explicit selection, rather than
rewriting the whole query with setText, which would lose caret/composition state.
Finish composition only when the user explicitly accepts a suggestion; do not
interfere with composition on ordinary keystrokes. Avoid text/selection callback
feedback loops and callbacks that capture stale state. Preserve current behavior
for callers that do not opt in. Back first dismisses suggestions; subsequent Back
retains the existing keyboard/navigation behavior.

Define integration checks for selection, composing text and keyboard input routing
in the existing Android test infrastructure (`tests/`); pure range transformations
belong only in ClipboardSearchTest. Run the unit suite and assembleUnstableDebug.
Device-dependent editor checks must be reported as unrun if no device is available;
do not gate ordinary development on obtaining the user's device.

### 4. Connect matching and suggestion UI on both surfaces

Add `actions/clipboard/ClipboardTagSuggestions.kt`: shared lightweight Compose rows,
explicit category labels, matching-prefix emphasis, count and click callback.
Use inline bounded panels that keep editor focus, not focus-stealing popups.

App: up to six rows below focused search. Keyboard: at most three compact rows
inside the existing action window height, leaving usable results below; remaining
suggestions can scroll within the bounded tray. Use current theme and accessibility
semantics, large enough touch targets, stable tag keys, and DPAD/Enter selection
when hardware keys are available. Do not use Enter to accept an unhighlighted row.

Show suggestions for a nonempty active token; after accepting a tag and a trailing
space, offer locally common remaining tags. For an initially empty field, keep
results unobstructed until explicit typing. Retain a stable maximum panel footprint
while updating suggestions to avoid repeated grid jumps. Closing search, losing
focus, selection mode, locked/incognito/disabled history must dismiss/disable it.
No-history or no-tags states must still permit ordinary search.

Use the same parsed-query evaluator and index for app Clips, app Archives and
keyboard matches. Preserve existing text matching for queries with no operators.
Accepting a suggestion applies the completed query immediately and requests the
relevant grid's top position; ordinary typing retains the existing 150 ms debounce.
A suggestion refresh, cursor move or tag-index update must not reset scroll.
Prevent the app header from collapsing during editing or suggestion interaction.

Verify `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.clipboard.*' assembleUnstableDebug` exits 0.

## Acceptance and verification

- Parser tests: plain phrases/URLs unchanged; exact positive/negative matching;
  multiple clauses; quoted names; case/underscore normalization; unknown tags;
  incomplete syntax; duplicate clauses; Unicode and mid-query selection/replacement.
- Index tests: count cards rather than images; same union/exclusion semantics on
  both surfaces; no unrelated clip associations; no obsolete memberships after
  deletion/re-tag/import; stable ordering; no self-suggestions.
- Existing ClipboardArchiveUiTest owns compatibility checks for old free-text
  matching. Do not duplicate the new parser's unit cases in that file.
- Editor integration checks: accepting mid-query, active IME composition, paste,
  backspace, selected text, focus retention and unchanged input destination.
- Deterministic synthetic workload: 10,000 unique labels and 10,000 card memberships;
  compare a fixed prefix/query corpus against a simple exhaustive reference.
  Report build and query timings separately after warmup; avoid flaky wall-clock
  assertions in ordinary unit tests. No performance claims from build success.
- Run the full clipboard unit target plus debug assembly; `git diff --check` passes.
- Check UI layouts for app and compact keyboard, including empty results and large
  font sizes. Report any unavailable runtime validation explicitly.

## Scope, dependencies and maintenance

No new library for v1: existing Compose/native EditText and Kotlin collections own
these requirements. Consider a specialized index only if the synthetic workload
shows a bottleneck; first optimize index invalidation, never scan all media on each
keystroke. Typo tolerance, remote booru aliases, full boolean syntax, chip editors,
search history, confidence controls and same-image queries are later work.

Allowed changes: the clipboard production files named above; the two new search/UI
files; ActionTextEdit.kt; clipboard tests; targeted editor tests under tests/;
localized strings following current repository conventions; this plan/index.
Do not modify inference models, tagging thresholds, persistence schemas, backup
format, networking or global keyboard suggestion behavior. Keep AI tags separate
from provider metadata. Future tag deletion, re-tagging and archive resolution
changes must invalidate index membership consistently.

Before implementing, run `git diff --stat 13af9e2bce..HEAD -- java/src/org/futo/inputmethod/latin/uix src/test/java/org/futo/inputmethod/latin/uix/actions/clipboard` and reconcile drift.
If clips cannot be reliably associated with archives using the existing resolver,
report that gap instead of inventing URL/filename matching. If editor changes
require replacing IME routing or tag queries require a persisted schema change,
revise the plan before extending scope. Use normal imperative commit messages;
create a codex/ branch if isolation is needed. Do not push without instruction.


## Implementation verification (2026-09-17)

Implemented using existing Kotlin/Compose/native editor dependencies. Tag storage
remains per media; only the transient search index aggregates by archive card.
The clipboard unit suite, debug APK assembly and native editor test compilation
pass. Compact and 1.5x-font suggestion previews were rendered and inspected.
Native editor instrumentation tests have not been executed on an Android runtime;
full-screen touch/IME behavior and device performance remain unverified. The pure
search suite includes a deterministic 10,000-label workload checked against an
exhaustive reference; its timing is a host JVM measurement, not a device benchmark.
