# Telno

Android app that makes and receives phone calls on the user's Telnyx number —
reliable in both directions, with near-zero idle battery cost (Kotlin, Compose,
single `:app` module, Telnyx Android WebRTC SDK). Product and architecture
decisions live in `SPEC.md`; the phased plan lives in `TODO.md`. This repo
mirrors the engineering conventions of the sibling Simmo repo
(`mikelward/simmo`); when a convention is underspecified here, that repo's
`AGENTS.md` is the tiebreaker. Phomo (`mikelward/phomo`) is the other sibling:
its `PUSH.md` is the analysis that led to Telno's architecture (Telno is that
document's "Option 6 on Telnyx" built as its own app), and its `SPEC.md` is the
nearest prior art for a calling app's Telecom integration.

## Project documentation

- Keep `SPEC.md` up to date when changing product behavior, architecture, the
  login/push lifecycle, the Telecom integration, persistence, permissions,
  navigation, or testing strategy.
- **`SPEC.md` records product, functionality, and architecture decisions — not
  low-level implementation detail.** It captures *what* Telno does and *why* a
  design was chosen (the vendor-SDK decision and the portability it trades
  away, the zero-idle battery model, the push-woken inbound path, how the
  self-managed `ConnectionService` and the SDK divide responsibility), so a
  reader can understand and QA the product from the spec. Ask "would this still
  be true and worth stating if the implementation were rewritten?" — if not,
  leave it in the code and its comments.
- Keep `TODO.md` current: check items off as they land, add newly discovered
  work to the right phase.

## Engineering quality bar

These are the principles, in priority order. Where a specific rule below seems
to conflict with one of them, the principle wins and the rule is what needs
fixing.

1. **Never drop a call, and never fail silently.** A dropped or unplaceable
   call is the worst outcome this app has — with one inbound-specific twist
   that makes it worse than it sounds: **a phone that silently stops ringing.**
   A dead push token, an expired credential, an unbound FCM registration, and a
   revoked permission all fail the same way — the caller hears ringing or
   voicemail, the phone does nothing, and the user learns about it days later
   as "why didn't you pick up?" with no evidence anything was ever wrong. If
   Telno can't do the right thing, it does the safe thing **and says so** — a
   notification where the user is looking, a visible reachability state on the
   home screen, and always a line in the debug log with the reason.
2. **Never lose the user's work.** Credentials, settings, names — the surface
   is smaller than a rules app's, but the rule is the same: data loss is never
   a side effect of a design decision, and where a real constraint seems to
   force it, the loss is a last resort after the alternatives are exhausted,
   and any genuine trade-off is the user's to make, stated plainly.
3. **Choose the battery-efficient architecture; avoid unnecessary background
   work.** Battery is a first-class product constraint, and it is won or lost
   while nothing is happening. What's banned is the always-running pattern: a
   persistent socket, a foreground service outside a call, polling, a
   keep-alive, a retained wakelock, a standing `ConnectivityManager` callback
   held just in case. This is **not a blanket ban on background work** — work
   with a real job that can only run in the background (the canonical case: a
   deferred retry of a token re-bind that failed offline, without which the
   phone silently stops ringing) belongs there, provided it is event-driven or
   deferred rather than periodic, scoped to finish and stop, and called out
   explicitly in the PR with why it can't run at the edges of a call. The
   steady-state idle footprint stays the FCM token binding — a long-lived
   address the provider pushes to, not a lease the device refreshes.
4. **Do the work at the edges of the call, not in the middle.** What a call
   needs should be prepared when the call starts (login,
   socket, audio route) and torn down when it ends. Within a call attempt,
   nothing that blocks a thread belongs in front of ringing, connecting, or the
   first frame of the in-call screen.
5. **Show feedback as early as possible.** Appearing to do nothing is its own
   failure — the user can't tell it apart from being broken, and they act on
   that (tapping again, giving up, missing the call). The incoming-call screen
   and the outbound status surface appear **immediately**, with real state as
   it becomes known; what's banned is delaying the frame, not a spinner inside
   it.
6. **Say why.** Every non-obvious action gets its reason recorded where the
   next reader will need it — a comment for a subtle mechanism, the debug log
   for a call-path decision, the PR for a design trade-off.

Concretely, every change must hold the line on **correctness, battery
efficiency, call reliability & audio quality, and inbound reachability**:

- **Correctness**: the change matches `SPEC.md` and the user's stated intent,
  handles the obvious edge cases (no network / cellular-only / captive Wi-Fi,
  permission denied, login failure or timeout, the far end rejecting or not
  answering, an incoming cellular call arriving mid-call, airplane mode, a push
  arriving with no credentials configured, a push arriving after the caller
  hung up, a rotated FCM token, process death, configuration change), and
  preserves existing invariants. One stays hard: **emergency calls are never
  placed through Telno** — they belong to the platform dialer and the SIM.
  New behavior is covered by a unit test; when fixing a bug, add a test that
  fails before the fix and passes after.
- **The wake-to-ring path.** Inbound is Telno's reason to exist, and it runs as
  a race against a human hanging up: FCM message → process wake → SDK
  connect/login → Telecom `addNewIncomingCall` → ringing UI. Telnyx holds the
  call while the device wakes, so the budget is seconds, not milliseconds — but
  every step must be non-blocking, every failure must resolve to something
  visible (a missed-call notification and a debug-log reason, never a swallowed
  push), and the path must be exercised by unit tests as a pure state machine
  with the races above as table rows. **Token rotation is part of this path**:
  `onNewToken` must re-bind with the provider, because a stale binding fails
  silently as "the phone never rang." Any change touching the push path, login
  lifecycle, or `ConnectionService` must state in the PR what the wake path now
  does and what happens on each failure.
- **Battery efficiency**: the default posture is **connect on demand, tear down
  completely when the call ends**. See principle 3; the PR calls out any
  exception explicitly.
- **Call reliability & audio quality**: signaling and media must be robust on
  real mobile networks. The Telnyx SDK owns NAT traversal, codec negotiation,
  and echo cancellation — prefer its proven defaults over hand-rolled media
  handling. The audio focus / routing / Telecom handshake must be correct so
  the call interacts properly with the earpiece/speaker/Bluetooth, the
  proximity sensor, and any concurrent cellular call. A call that connects but
  has one-way audio, echo, or seconds of dead air before ringback is not done.
- **Jank-free UI**: usual Compose discipline (`remember`/`derivedStateOf`/
  stable types, no I/O in composition). Render screens from in-memory state;
  where something genuinely isn't ready, show the screen anyway and fill it in.

When you cannot verify one of these locally (the sandbox has no cellular radio,
no microphone, no FCM delivery, and no real call peer), say so explicitly in
the chat update — "verified by unit test; call and push behavior need a device
check" — rather than implying all were checked. Real-call and real-push
verification on a physical device is the pillar most often still owed.

## Spacing

Stick to a 4dp grid for every padding/margin/spacing value (`4`, `8`, `12`,
`16`, `24`, ...); reuse values already used by sibling composables; symmetry by
default, and any asymmetry gets a one-sentence justification in the PR. Flag
off-grid or inconsistent spacing you notice even outside the diff (file, line,
proposed fix) without silently fixing it in the same commit.

## Git workflow

- **These rules assume an `origin` remote.** If the environment supports remote
  Git, the absence of `origin` is a configuration error: say so and stop rather
  than improvising a local substitute. Sandboxes without remote Git support may
  continue on the provided local branch without fetching or rebasing from
  `origin/main`; commit the work locally, clearly report that it was not pushed
  and that no PR was opened, and leave remote Git operations for a capable
  environment.
- **Branch naming.** Feature branches are prefixed with the agent's own short
  name: `<agent>/<short-topic>` (`claude/...` for Claude Code, `codex/...` for
  Codex, and so on). One topic per branch; never commit to `main`.
- **Merge cue (`merged` / `I merged` / `landed` / merge webhook) runs hygiene
  *before* engaging with the rest of the message:** `git fetch origin main`,
  cut a fresh `<agent>/<short-topic>` branch off `origin/main`, announce the
  switch. Where the sandbox has no remote, say so and ask for a synced checkout
  rather than branching off a stale `main`.
- **After a merge, take a fresh `<agent>/<short-topic>`** — don't reset the
  merged name onto the new base. When a sandbox pins the branch name so a fresh
  one isn't available, say so and ask before resetting it; no short check
  reliably separates "already merged" from "not yet merged," and guessing costs
  someone their work.
- In environments with remote Git support, always start work from the latest
  `origin/main`: `git fetch origin main` and rebase the working branch onto it
  before the first commit, even when the branch already exists. Resolve
  conflicts rather than abandoning the rebase.
- **Use `git worktree` when it's available.** Give each branch its own worktree
  instead of switching branches in place.
- **Structure the branch as a sequence of logical commits, rebasing and
  squashing as needed.** Each commit is one coherent change that stands on its
  own — buildable and green by itself. The repo rebase-merges, so every commit
  lands on `main` individually with its own subject, blame lines, and bisect
  step.
- Clean up the unmerged commit history before requesting review and again
  before merge (`git rebase -i origin/main`). After rewriting, force-push with
  `git push --force-with-lease` (never bare `--force`). Ask before rewriting
  commits that have been individually reviewed.
- **Unshallow before answering anything that depends on git history depth.**
  If `git rev-parse --is-shallow-repository` says `true`, run
  `git fetch --unshallow origin main` before reporting any versionCode or
  commit count.

## Commit messages

- Write every subject for end users, sentence case, plain English, no internal
  symbol names, ≤ ~70 characters; engineering detail goes in the body. Every
  release-worthy commit subject in a push to `main` ships as a bullet in the
  Firebase and Play "What's new" list once the release pipeline lands (it
  mirrors Simmo's; see that repo's `AGENTS.md` for the mechanics).
- Because the repo rebase-merges, the PR title never lands on `main` — each
  commit's own subject does. Title **every** commit on the branch by these
  rules, not just the PR.
- Keep non-user-facing commits out of release notes with a subject prefix, used
  precisely (the prefix is a promise the commit has **no user-visible
  effect**): `ci:`, `docs:` (`docs/PRIVACY.md` excepted — it backs the hosted
  privacy policy, so it is user-facing), `internal:`, `refactor:`,
  `test:`/`tests:`.
- **Housekeeping paths are dropped whatever the subject says** — a commit whose
  every changed path is a `.md` file or a root dotfile/dotdir never reaches the
  notes (again `docs/PRIVACY.md` excepted). Prefix those commits anyway, so the
  subject never reads like a shippable bullet.
- Play caps "What's new" at 500 characters per language — don't line up a long
  stack of small commits when one of them tells the user-facing story on its
  own; squash the supporting work into it.

## Autonomy

- **Open the PR without being asked.** Pushing a finished branch and opening
  its pull request are one step, not two. The exception is an explicit
  instruction not to ("just commit", "no PR yet"), which holds until the user
  lifts it. This file is the repo owner's standing request for that PR.
- **Opening the PR includes wiring up the watch.** In the same step, subscribe
  to the PR's activity (`subscribe_pr_activity`) *and* arm the first scheduled
  check. Both, not either: webhooks drop events, and a PR that is only
  subscribed looks watched and silently isn't.
- **Poll your own open PRs every 5 minutes** — the ones you opened or were
  explicitly asked to watch. Never end a turn by going idle with one of yours
  still open: arm the next check with whatever the client offers (`send_later`,
  a scheduled task / cron, `/loop`), and arm it *without asking*. Merging
  doesn't end the watch either: drop to a slower cadence (every half hour or
  so) and keep handling late comments.
- **Three polling states, so the 5-minute cadence has an end.** Five minutes is
  for a PR with something outstanding: CI running, a review requested, a
  comment unanswered, a merge conflict. Once a PR is green, reviewed, and has
  nothing left but the merge — or is merged and only waiting out late
  comments — drop to half-hourly. Stop entirely when it merges or closes and
  the late-comment window has passed.
- **One pending check per PR, not one per wake-up.** Before arming, reuse or
  cancel the pending one (`update_trigger`, or `delete_trigger` then re-arm) so
  exactly one check is outstanding.
- **"Drive" means run the loop automatically**: pick the next task, implement
  it, open the PR, wait for the automatic review, address every comment, merge
  once CI is green and the reviewer has left its thumbs up — then pick the next
  actionable `TODO.md` item and go around again. Driving ends when the work
  runs out or the user says stop, not when one PR merges.
- **A red baseline is the next task.** Before pulling anything from `TODO.md`,
  run `./gradlew test` and `./gradlew lint` and get them green. A preexisting
  failure is work to do, not a thing to classify as "unrelated" and step
  around. Fix it first (as its own first commit), then pick the task.
- **"Autopilot" is drive without blocking on the user.** Wherever drive would
  stop and ask, autopilot takes its best guess and keeps going, preferring the
  option that is cheapest to undo or change later. Record each guess in
  `TODO.md` under a `Decisions needing review` heading. Destructive or
  irreversible actions outside the loop — rewriting shared history, deleting
  work, anything reaching a system beyond this repo — still wait for a real
  answer. Privacy uncertainty is never inside the loop either: if you can't
  tell whether something is user data, it waits for a real answer.

## Working with PRs

- Prefer the `mcp__github__*` MCP tools for GitHub operations; the `gh` CLI is
  not installed in the sandbox. If your client exposes neither, say so rather
  than guessing at the outcome of an operation you couldn't perform.
- **"Drive to merge"**: open the PR, wait for the automatic review, address
  every review comment — fix it if you agree, reply on the thread saying why if
  you don't — and merge once CI is green and the reviewer has left its thumbs
  up.
- **Codex is the automated reviewer on this repo, mirroring Simmo** — update
  this line if the repo ends up wired to a different bot. Its reviews are
  triggered automatically; you don't request them.
- **Address review comments automatically — don't wait to be asked.** Fold the
  fix into the commit it belongs to (rebase / `--fixup`) rather than tacking on
  an "address review" commit.
- **`resolve_review_thread` works — pass the thread ID (`PRRT_*`), not a
  comment ID (`PRRC_*`).** Reply *then* resolve.
- **Report when the reviewer finishes reviewing a fresh push** — a one-liner
  naming the SHA and comment count, tied to the *latest* pushed SHA.
- **Judge every review comment on merit, whoever wrote it.** Verify the claim
  before acting; if it doesn't hold up, reply saying why and decline. Never
  leave a review comment thread silently dismissed.
- **Report the Android `versionCode` after every merge to `main`** once the
  Gradle scaffold lands (`git rev-list --count origin/main`; unshallow first).
- Link every open PR in the stack (one URL per line) whenever you push,
  summarize CI, or invite review. Refresh the PR title and body on every push
  so they describe the full, latest state of the branch.
- Keep watching merged PRs for late review comments; stop once every post-merge
  comment is handled *and* the PR has gone ~24h without a new one.
- Skip echo events silently: if the body matches a comment you just posted,
  it's your own echo.
- On CI failure: check for the failing-tests PR comment first; no comment means
  the failure is earlier than tests (compile, lint, resource merge). The PR
  `build` job builds `refs/pull/<N>/merge` — your branch *merged with main* —
  so reproduce with `git merge origin/main --no-commit` before bisecting your
  own commits. Check whether the failure is pre-existing on the base commit
  before debugging.

## Talking to the user

- **One question at a time.** Never stack multiple questions in a single turn —
  ask the most important one, wait for the answer, then ask the next if you
  still need it.
- **Don't interrupt.** Never fire off a question while the user is still
  typing.
- **Keep replies short — don't dump a full page.** Lead with the single most
  important point and stop.
- **End the turn by restating any pending decision.** If you're waiting on an
  answer, the last line of the reply is that question, written out in about a
  sentence. Nothing pending, no line.

## Asking questions

- Ask questions as plain chat messages. Claude specifically: never use
  `AskUserQuestion` — it's broken in the Claude mobile app.
- After asking, stop and wait for the answer. Don't proceed on an assumed
  answer or keep working on the part the question affects.
- Acknowledge every answer explicitly before acting on it.
- Whenever you change direction — because of an answer, something discovered in
  the code, a failing check — say so immediately in chat: what changed, and
  why.

## Error handling

- **Don't silently swallow exceptions.** Every catch block needs to **log** the
  exception with enough sanitized context to identify the failed call (never a
  phone number, contact name, credential, or push token — the *Privacy* rule
  applies to logs too), **clean up** what the `try` block acquired
  (`use { … }` / `finally`), and **handle the edge case explicitly** rather
  than letting control fall through. Catching `Throwable` (or a blanket
  `Exception`) also swallows `CancellationException`, which breaks structured
  concurrency — narrow the type, or rethrow `CancellationException` first.
  **On the wake-to-ring and place-call paths this rule has teeth:** a swallowed
  exception between an FCM push and the ringing UI is precisely how a phone
  silently stops ringing; every catch there must still end in a visible
  outcome — a missed-call notification, a failed-call state, a debug-log
  reason — never a silent fall-through. If you genuinely do want to ignore a
  specific failure, name the reason in a one-line comment and still log at
  debug so it's traceable.

## Privacy

- **Never put user data in any artifact that leaves this machine.** That
  includes commit subjects and bodies, PR titles / descriptions / comments,
  review replies, issue text, branch names, code comments, test fixtures,
  screenshot snapshots, and anything else that ends up on GitHub, the Play
  Console, or in logs. This app handles PII by definition — **phone numbers,
  call history, contact names, the user's own Telnyx number, SIP connection
  credentials, push tokens, and the countries they call**. None of it goes into
  a commit, a PR, a bug reproduction, or a test fixture. If a user-supplied bug
  report contains real numbers, paraphrase — don't quote verbatim. When in
  doubt, ask before pushing.
- **The test is whether a value is somebody's, not whether the name is real.**
  A canned example carrier or provider name is a stock stand-in and is fine.
  Numbers and identifiers take obviously-fake values (`+15550100`). What is
  banned is a *particular person's* data lifted from a device or a bug report.
- **The on-device debug log is the one sanctioned exception, and a narrow
  one.** Diagnosing a failed call or a dead push path is a hard product
  requirement, so the log may carry **coarse call and reachability state**: a
  dialed number's country calling code, call direction and state transitions,
  SDK/Telecom error codes, and push-binding health events. **The floor is
  absolute**: never a full dialed number, a contact's name or number, a
  credential, or a raw push token. Above the floor the test is need, not
  category. `docs/PRIVACY.md` must describe what the log carries before any
  sharing feature ships. The rule above is unchanged for everything else.
- **The privacy policy protects users without painting the product into a
  corner** (owner guidance). Write `docs/PRIVACY.md` to preserve user privacy
  and to be true of the app as shipped — not to encode the narrowest possible
  interpretation as a permanent promise. Absolute claims ("never", "no
  servers", "exactly two parties") are cheap to write and expensive to walk
  back; prefer present-tense statements of what the app does today, and weigh
  functionality, data-loss protection, performance, cost, and simplicity
  alongside privacy before committing a claim that would constrain them. The
  load-bearing invariant is process, not phrasing: the policy is updated
  *before* any behavior that touches new data ships, so users are never told
  less than the truth — and the policy never promises more than the product
  has actually decided. **Informed user consent typically unlocks what
  defaults keep closed.** The owner's example of the approximate shape —
  not a definition: redacted logs sent to analytics or crash services, and
  data backups, can all be fine with consent. The boundaries themselves are
  product judgments made case by case, weighing privacy against
  functionality, data loss, performance, cost, and simplicity — not derived
  from this bullet as if it were a spec, and taken to the owner when
  genuinely uncertain (see Autonomy: privacy uncertainty waits). The
  debug-log content floor above stands as today's deliberate choice;
  everything else describes, it doesn't foreclose.

## Language and spelling

Use US English everywhere people read English: user-facing strings, commit
subjects and bodies, PR titles/descriptions, comments, KDoc, identifiers, docs
(`SPEC.md`, `TODO.md`, `docs/`), and this file. The forms these repos keep
getting wrong: **`gray`** (not "grey") and **`canceled` / `canceling`** (one
`l`). Others: `color`, `behavior`, `dialog`, `-ize` over `-ise`, `license`,
`center`, `labeled`, `traveling`. Platform/third-party API spellings stay as
the framework spells them (`CancellationException` keeps its double `l`). This
is about US-vs-UK spelling, not about adding locales.

## Concise copy

Keep user-facing text short. A label, action, or title should carry only the
words the user needs — drop framing verbs and prefixes the surrounding UI
already implies. Prefer the shortest phrasing that stays unambiguous; when a
longer form is genuinely needed for clarity, say why in the PR.

## Translations

English first, translations in a second PR — never the same PR. Propose new
English copy in chat and get explicit approval before translating. New base
strings land with a per-string `tools:ignore="MissingTranslation"` and a
`<!-- TODO: translate -->` comment; the follow-up translation PR fans the
approved copy out to every locale and removes both. Escape apostrophes (`\'`)
in any locale's string resources.

## Remote build environments (Cursor Cloud and Claude Code on the web)

- **JDK 21** is pre-installed. **Android SDK** lives at `/opt/android-sdk`
  (`ANDROID_HOME`). On Claude Code on the web the SDK is *not* pre-installed;
  the `SessionStart` hook at `.claude/hooks/session-start.sh` (mirroring
  Simmo's) provisions it at session start. If `/opt/android-sdk` is missing
  pieces mid-session, run `CLAUDE_CODE_REMOTE=true
  .claude/hooks/session-start.sh` rather than hand-installing.
- Key commands: `./gradlew assembleDebug`, `./gradlew test`,
  `./gradlew lint`, `./gradlew clean`.
- **No emulator practicality**: KVM is unavailable in the remote environments,
  and no emulator has a cellular radio, microphone, or FCM delivery anyway —
  call and push flows need a real device. Say so when reporting verification
  status.

## Testing expectations

- Code changes must include or update unit tests; product logic belongs in the
  pure domain layer where it is testable without Android. The call state
  machine — including every inbound race (push with no credentials, push after
  hangup, push during a cellular call, rotated token) — is pure logic and must
  be unit-tested exhaustively; these are exactly the parts that fail silently
  and lose a call.
- UI changes must include or update Robolectric + Roborazzi screenshot tests,
  wired into `.github/workflows/android-ci.yml` on an explicit `--tests`
  allow-list (one step per screenshot class) — a class not on the list never
  records in CI even when it passes locally.
- Run `./gradlew test` and `./gradlew lint` before pushing when the environment
  can; otherwise say clearly what was verified by inspection only.
- **Fix any preexisting test failures as the *first* commit of the series.** If
  the failure is genuinely unrelated and out of scope, say so in the first
  response and confirm before skipping past it.
- **Don't paper over racy / flaky tests** with `Thread.sleep`, a retry loop, or
  a bumped timeout — make the ordering explicit. **Don't disable a failing
  check** to make it pass — fix the underlying issue.
- **Verify the sandbox state before assuming it either way** (`command -v
  sdkmanager`, `ls /opt/android-sdk`, a `curl` probe at
  `https://maven.google.com/`) before concluding the build can't run here.

## Cost and reliability

- **Call out cost and reliability up front** when recommending new
  infrastructure or a new external call. Include a rough dollar figure and note
  reliability implications: new failure modes, rate limits, added latency,
  extra points of failure, and what the user sees if the dependency is down.
  On this app that starts with Telnyx itself: SDK-originated calls are billed
  on the SDK rate card, not the trunk card, and per-destination rates vary by
  more than an order of magnitude — quote the destination actually called, not
  a US headline number. If the impact is effectively zero, say so rather than
  omitting the note.

## CI timing

- **Report significant CI timing regressions** (rule of thumb: >25% or >30s on
  a job under ~5min) after CI finishes on a push, comparing like with like —
  PR against PR, `main` against `main`. Name the likely cause; don't narrate
  routine wobble.
