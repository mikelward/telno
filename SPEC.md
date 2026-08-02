# Telno Design Spec

Telno is a minimalist Android app that **makes and receives** phone calls on
the user's Telnyx number — reliably, in both directions, with near-zero battery
cost while idle. It is the productization of the architecture Phomo's `PUSH.md`
calls "Option 6 on Telnyx": drop SIP entirely and build both call legs on one
vendor's WebRTC SDK, letting the provider own the always-on part — the push,
the wake race, and holding the call while the phone wakes up.

> **Status.** This spec describes the intended v1 product and architecture.
> The repository contains the project scaffold (buildable app shell, home
> surface, CI); the calling implementation lands across the milestones in
> `TODO.md`. Where this spec relies on a claim that has not been verified
> against the live Telnyx platform, it says so — the two load-bearing ones
> are marked **[verify]** and are the first milestone's job.

## Product shape

- **Inbound and outbound are both first-class.** This is the defining
  difference from sibling Phomo, which is outbound-only precisely because an
  inbound-capable *SIP* client must stay registered around the clock. Telno
  escapes that trade-off by not being a SIP client: Telnyx sends a
  high-priority FCM push when a call arrives, holds the call while the device
  wakes, and hands the app a ringing call object. Reachability comes from a
  long-lived push-token binding, not from anything running on the device.
- **One vendor, one stack, accepted deliberately.** Both legs go through the
  Telnyx Android WebRTC SDK. There is no liblinphone, no SIP stack of our own,
  and no second media engine. The cost is portability: changing provider is a
  rewrite of the calling layer, not a credentials edit. `PUSH.md` §6 and §12
  argue that for one user with one provider this is worth trading for the
  enormous machinery it dissolves (push delivery, registration lifecycle, NAT
  traversal, the wake race — all become the provider's problem); this spec
  records that trade as taken. If provider portability ever becomes a
  requirement, that is a product pivot, not a refactor.
- **Plausibly backend-free — the deciding advantage of Telnyx.** The Telnyx
  SDK authenticates with ordinary SIP connection credentials via credential
  login — no JWT-minting endpoint, unlike Twilio's Voice SDK — and a number
  assigned to that connection appears to route inbound calls to the SDK
  without a webhook. **[verify]** Both claims are from documentation, not from
  a live call; confirming them (or discovering the minimal TeXML/webhook
  configuration actually required) is the first spike in `TODO.md`. If a
  webhook turns out to be required, it is provider-side configuration (a TeXML
  bin), still not a server of ours.
- **Bring your own Telnyx account.** The user supplies Telnyx SIP connection
  credentials and owns their number, billing, and provider relationship.
  Telno is a client, not a calling plan; it does not buy numbers, and number
  eligibility per country (`PUSH.md` §9) is out of app scope.
- **A second line, not a SIM replacement** (owner decision). Telno's number
  is a secondary line backed by the provider's voicemail, and `PUSH.md` §12
  says that calibrates all the reliability work: a few seconds of ring
  latency is invisible, a rare missed ring falls to voicemail rather than
  silence, and aggressively chasing OEM push-deferral mitigations
  (battery-exemption prompts) is not worth their cost by default. What the
  bar does *not* relax is honesty — the reachability indicator still tells
  the user whether the push path is believed healthy; it just doesn't promise
  carrier-grade delivery.
- **Routing policy belongs to Simmo.** Sibling Simmo owns the native-dialer
  redirect and per-country routing rules; Telno never registers a
  `CallRedirectionService` and never competes for that role (owner decision).
  Telno's integration surface is the hand-off intent below, which slots into
  Simmo as a call action the way its Google Voice hand-off does.
- **Stay out of the way.** The app's surface is small: setup, a dialer, an
  in-call screen, and a visible reachability state. It is a utility, not a
  destination app.

## Simmo integration (the hand-off intent)

Simmo's rules engine decides which app carries an outgoing call; "use Telno"
must work there like a second line — no contact entry, no extra tap. Simmo's
existing Google Voice action *prefills* a dialer and leaves the user to
confirm; Telno's hand-off instead **places the call immediately**:

- **Telno handles `ACTION_CALL` with a `tel:` URI** (targeted at Telno's
  component, or chosen via Simmo's action picker) by placing the call at
  once — no confirmation screen, no prefilled dialer. `ACTION_DIAL` prefill
  is also honored for completeness, but the integration exists precisely so a
  redirect completes without another tap.
- **Protected like the platform's own call intent.** The entry point is
  exported but requires the calling app to hold `CALL_PHONE` (Simmo
  qualifies), mirroring `ACTION_CALL` semantics, so an arbitrary app cannot
  silently place billable calls through Telno.
- **Emergency numbers are refused here too**, loudly: the attempt is handed
  to the platform dialer, never silently dropped (see Telecom integration).
- **A handed-off call that can't be placed fails visibly** — no credentials,
  no network, login failure all end in a failed-call state and a debug-log
  reason. From Simmo's side the call was already redirected, so a silent
  no-op here is indistinguishable from a dropped call, principle 1's worst
  case.

The Simmo-side action (adding Telno to its hand-off targets) is Simmo-repo
work and tracked there.

## Devices and compatibility

- First-class targets: recent **Pixel** and **Samsung** devices on **Android
  16+** (`minSdk 34` as a courtesy, designed and tested against 16+),
  mirroring Phomo. These two OEMs differ in Telecom, Doze, and
  background-execution behavior — and OEM push deferral is a live reliability
  risk (`PUSH.md` §14) — so both are part of "done" for any call-path or
  push-path change.
- Android-only, by design. No iOS, no web.

## Calling stack

- **Telnyx Android WebRTC SDK** for signaling (the SDK's WebSocket protocol)
  and media (WebRTC: Opus, echo cancellation / AGC / noise suppression, NetEQ
  jitter buffer, FEC, ICE/STUN/TURN). `PUSH.md` §8A establishes this is not a
  media-quality compromise relative to a SIP stack — same RTP/SRTP lineage as
  Chrome and Meet, BSD-licensed rather than GPLv3.
- The SDK registers the FCM token at login and supports up to **5 push tokens
  per user** (least-recently-used evicted), so a phone and a tablet can ring
  together. Multi-device is not a v1 feature, but the design must not preclude
  it.
- Prefer the SDK's defaults for codec negotiation, NAT traversal, and audio
  processing. Hand-rolled media handling is how a calling app gets one-way
  audio on exactly the network the developer didn't test.

## Battery model (no always-on machinery)

Battery efficiency is a first-class product constraint, and it is won or lost
while nothing is happening. The enemy is the always-running architecture —
wakelocks held while idle, polling, keep-alives — not background work as a
category.

- **While idle, Telno runs nothing by default.** No persistent socket, no
  foreground service, no periodic re-login or polling job, no keep-alive, no
  retained wakelock, no standing connectivity callback. The steady-state idle
  footprint is the FCM token binding held server-side by Telnyx — a long-lived
  address the provider pushes to, not a lease the device must wake to refresh.
  This is the genuinely-zero-idle shape `PUSH.md` §12 identifies for vendor
  SDKs.
- **Outbound: connect on demand.** Tapping Call brings the socket up, logs in
  with the stored credentials, places the call, and on hangup tears the stack
  down completely.
- **Inbound: push-woken.** An FCM high-priority message wakes the process; the
  app connects, logs in, receives the ringing call, and posts it to Telecom.
  Telnyx holding the call during the wake removes the *infrastructure* race —
  nothing of ours must already be running when the call arrives — but not the
  *human* one: the caller can hang up mid-wake, which is exactly the
  push-after-hangup row in the state machine. The adapter therefore confirms
  the call is still live before posting it to Telecom, and resolves a stale
  attempt as a missed call, never a phantom ringing UI. The path must be fast
  and must never fail silently.
- **The whole call attempt is protected and time-bounded, not just the
  active call.** A push's own execution grace ends when its handler returns,
  so an unprotected process can be killed mid-login and drop a call that
  never got to ring. The spec fixes the window and the outcomes, not the
  mechanism: from push receipt (inbound) or call placement (outbound) until
  ringing or teardown, the attempt must survive; the pre-ring stretch is
  bounded in time; and every exit path ends in a ringing call, a completed
  call, or a terminal visible failure (missed-call record, unhealthy
  reachability, debug-log reason) with full teardown — never an open-ended
  background login, and nothing left running while idle.
- **Necessary background work is allowed — the bar is justification, not
  category.** Work that genuinely needs the background belongs there when it
  is the battery-efficient way to do its job: event-driven or deferred rather
  than periodic, scoped to finish and stop, and called out in the PR with why
  it can't run at a call's edge. The canonical case is retrying a token
  re-bind that failed offline — skipping it to stay ideologically idle would
  trade a silent unreachable phone for a negligible battery win, which is the
  wrong side of principle 1.

## Reachability (the inbound correctness surface)

Not being a SIP client removes the registration *lifecycle*, not the idea of
staying reachable. What remains is a small, sharp correctness surface whose
failure mode is always the same: **the phone silently stops ringing.**

- **The token binding is the reachable address.** The FCM token is bound to
  the Telnyx connection at login. It must be re-bound on `onNewToken` (FCM
  rotates tokens at will), after credential changes, and after app data
  restore on a new device. Each of these, missed, is silent unreachability.
- **A binding attempt that fails has a terminal outcome, not just a retry.**
  A re-bind rejected while online, or a deferred retry that exhausts, leaves
  the provider pushing to a dead token — the silent-unreachability state
  principle 1 exists to prevent. Every failed binding attempt therefore
  durably records a sanitized reason in the debug log, marks reachability
  unhealthy, and surfaces it (notification, or the permission-independent
  fallback below). The retry is the recovery path; the visible unhealthy
  state is the guarantee.
- **A visible reachability state.** The home screen shows whether the app
  believes it is reachable — credentials configured, push token bound and
  current, notification channel intact — and when that state was last
  confirmed. `PUSH.md` §12 argues for this indicator on every inbound
  architecture: a silently dead push path must be something the user can see,
  not something they discover by missing a call. Confirmation happens
  opportunistically (app open, after token rotation, after a call) — never by
  periodic background job, which would violate the battery model.
- **Failure surfacing must not depend on notification permission.** Missed-call
  and failure notifications sit outside the CallStyle exemption, so with
  `POST_NOTIFICATIONS` denied they silently don't post. The notification is
  therefore the fast path, not the only path: every failed wake or login is
  durably recorded, flips the reachability state to unhealthy with the reason,
  and is surfaced prominently the next time any Telno UI is visible. A denied
  permission degrades how *soon* the user learns of a failure, never *whether*.
- **Known platform risks, watched rather than pre-solved:** FCM's
  priority-downgrade heuristic for low-volume apps and OEM battery optimizers
  deferring delivery (`PUSH.md` §3, §14) can both delay or drop the wake push.
  V1 observes (debug log + reachability state) rather than preemptively asking
  the user to exempt Telno from battery optimization — that prompt sits
  awkwardly against a product whose pitch is battery efficiency, and it is a
  recorded decision to add it only if real-device data shows it is needed.

## Telecom integration

- **Self-managed `ConnectionService`** (`MANAGE_OWN_CALLS`), with a phone
  account registered at setup. Both directions run through Telecom so the
  platform arbitrates audio focus, Bluetooth/earpiece/speaker routing, and
  concurrency with cellular calls.
- **Proximity handling is the app's responsibility.** A self-managed
  `ConnectionService` supplies its own in-call UI instead of the system
  dialer's, so nothing turns the screen off against the user's face unless
  Telno does — and a lit touchscreen on a cheek mutes or hangs up calls. The
  in-call screen holds the platform proximity wakelock
  (`PROXIMITY_SCREEN_OFF_WAKE_LOCK`) for the call's duration on earpiece
  routes; this is Phase 2 implementation and device-QA work in `TODO.md`.
- **Inbound UI**: `Notification.CallStyle` with a full-screen intent, gated on
  `canUseFullScreenIntent()`. Note (from `PUSH.md` §13): an app that declares
  `MANAGE_OWN_CALLS`, implements `ConnectionService`, and registers its phone
  account is **exempt from `POST_NOTIFICATIONS` for CallStyle notifications**
  — inbound calling must never be made to look unavailable because that
  permission was denied. The permission is still requested (contextually) for
  what sits outside the exemption: missed-call notifications and the
  reachability indicator.
- **Concurrent cellular calls**: an incoming cellular call during a Telnyx
  call, and a Telnyx push during a cellular call, are both first-class state
  machine rows, not edge cases. The platform's self-managed call arbitration
  handles the mechanics; Telno's job is to never leave either call in a state
  the user can't see or end.
- **Emergency calls are never placed through Telno.** They belong to the
  platform dialer and the SIM; the dialer screen refuses them with a clear
  hand-off to the platform.

## Call state machine

All call logic lives in a pure-Kotlin state machine (no Android dependencies),
unit-tested as a table, in the style Phomo's spec set out. The inbound races
are explicit rows: push arriving with no credentials configured; push arriving
after the caller already hung up; push arriving during an active cellular
call; a stale or duplicate push; login failing mid-wake; token rotated between
push and connect. Every row resolves to a defined outcome, and no row resolves
to silence — failures end in a missed-call notification (or the
permission-independent fallback above) and a debug-log reason.

**Process death gets a durable answer, not just a table row** — an in-memory
machine cannot observe its own death. The requirement is the outcome: an
inbound attempt the process died in the middle of must still become a
missed-call record, an unhealthy reachability state, and a debug-log reason
by the next time the app runs — never a silently vanished call. Restart
recovery is tested as restart behavior (kill between push and ring), not only
modeled as a synthetic row; how the in-flight attempt is remembered is the
implementation's choice.

## UI architecture

Single `:app` Kotlin module, Jetpack Compose, Material 3 with dynamic color,
light and dark first-class — mirroring Simmo's layering:

- **Domain layer** (pure Kotlin): the call state machine, number normalization
  (E.164, libphonenumber), reachability state derivation. Fully unit-tested;
  all product logic lives here.
- **Platform layer**: the `ConnectionService`, the `FirebaseMessagingService`,
  the Telnyx SDK adapter, permission/role plumbing. Thin adapters over the
  domain, kept too small to hide logic.
- **UI layer**: setup (credentials + permissions), home/status (reachability
  state, recent state), dialer, in-call screen, settings. State via
  ViewModel + StateFlow. Persistence splits by sensitivity: credentials live
  in the dedicated encrypted, backup-excluded store (see Persistence);
  ordinary settings use DataStore when they arrive. Every screen state gets a
  Roborazzi screenshot test wired into CI.

Screens render from in-memory state and appear immediately; the incoming-call
screen especially — a caller is waiting, and an absent frame is
indistinguishable from the phone not ringing.

## Persistence

- **Telnyx SIP connection credentials** are the only sensitive data stored:
  encrypted at rest, in a backup-excluded store (never carried off-device by
  cloud backup or device-to-device transfer — a restored token/credential set
  on another device would also silently steal the ring; see Reachability).
  A store that exists but can't be read (key loss, corruption) surfaces as
  "account needs attention" with setup as the recovery path — never as a
  fresh install that quietly invites overwriting it, and always with a
  debug-log reason. Setup itself stays reachable after credentials are
  saved, so a mistyped password never requires clearing app data.
- **Settings** are ordinary preferences and may be backed up.
- **No call history of Telno's own in v1** beyond what the platform records
  for self-managed Telecom calls. Adding one later is a product decision
  recorded here first.

## Permissions and roles

Requested **contextually**, at the point of first use, never as a wall at
first launch. `INTERNET` / `ACCESS_NETWORK_STATE` (install-time);
`RECORD_AUDIO` (first call); `MANAGE_OWN_CALLS`; `FOREGROUND_SERVICE` +
`_PHONE_CALL` / `_MICROPHONE` (the in-call service); `WAKE_LOCK`
(install-time — required to acquire the in-call proximity wakelock, the one
sanctioned wakelock and only ever held during a call); `POST_NOTIFICATIONS`
(missed calls + reachability — never a gate on the incoming-call UI, per
Telecom integration above); `USE_FULL_SCREEN_INTENT` (inbound ring);
`BLUETOOTH_CONNECT` (first headset use). The manifest starts minimal and grows
with the milestones, so the app never ships holding a permission it doesn't
exercise.

## Privacy

- In the intended architecture Telno has no backend, so user data goes to
  exactly two parties, both disclosed in `docs/PRIVACY.md` before launch:
  **Telnyx** (the calls themselves, the credentials, the numbers dialed) and
  **Google/FCM** (a push per inbound call, plus Firebase telemetry if and when
  it is enabled, following the siblings' gated `google-services.json`
  pattern). This holds only while the backend-free claim holds (**[verify]**,
  Product shape): if the Phase 0 spike finds a service of ours is required,
  the data-flow list and `docs/PRIVACY.md` scope change with it, and this
  section is updated before anything ships.
- The on-device debug log carries coarse call/reachability state only, per
  `AGENTS.md` — never full numbers, contacts, credentials, or raw tokens.

## Distribution and versioning

- `versionCode` = `git rev-list --count HEAD`; `versionName` =
  `"1.0.<count>+<shortSha>"`, derived at configure time — same scheme as the
  siblings.
- CI today is lean validation: build + unit tests + lint on every PR, plus
  screenshot recording uploaded as an artifact. The release pipeline —
  screenshot drift auto-commit, Firebase App Distribution, the Play internal
  track with commit subjects as "What's new", all secret-gated so forks and
  the sandbox build cleanly — mirrors Simmo's and lands when there is
  something worth distributing (`TODO.md` Phase 1 / Decisions needing
  review).
- **applicationId is `app.telno`** (owner decision, matching Simmo's
  `app.simmo`); display name **Telno**. Play build, CI tester (`.debug`), and
  local dev build (`.dev`) follow Simmo's applicationId-suffix scheme, with
  color-coded launcher icons telling the three co-installed builds apart
  (lettered badges deferred until real branding exists).

## Testing strategy

- **Pure logic is unit-tested exhaustively** — the call state machine (both
  directions, all races), number handling, reachability derivation.
- **UI is screenshot-tested** (Robolectric + Roborazzi) per screen state.
- **Real-device verification is irreducible** for everything that matters
  most: the sandbox has no radio, no microphone, no FCM delivery, and no call
  peer. Wake-to-ring latency on a Dozing Pixel and Samsung, audio quality both
  ways, Bluetooth routing, and cellular-call concurrency are device-QA items,
  tracked in a QA matrix as flows land. Changes to those paths are flagged for
  on-device testing every time and never reported as verified by inspection.

## Non-goals (v1)

- **SMS/MMS.** Inbound messages arrive by webhook, never to a client, so SMS
  reintroduces a server (`PUSH.md` §9). Out until the calling product is
  proven on-device.
- **Provider portability.** Deliberately traded away; see Product shape.
- **Voicemail of our own.** Missed calls fall to whatever the user configures
  provider-side (TeXML `<Record>` recipes exist); Telno's job is to make
  missing calls rare and visible.
- **Being the device's default dialer, handling the SIM's calls, or diverting
  native-dialer calls.** In the MVP Telno manages calls on the Telnyx number
  only: diversion and routing policy are Simmo's job (see Product shape), and
  Telno's entry points are its own UI and the hand-off intent. Acting as a
  dialer or call router is a possible v2 exploration (owner decision), not an
  MVP feature — nothing in v1 should preclude it, but nothing builds toward
  it either.
- **Buying numbers in-app**; multiple accounts; call recording; iOS.

## Open questions

Ordered by how much each answer changes the plan. (Three earlier questions
are answered and folded in above: this is a secondary line, routing belongs
to Simmo via the hand-off intent, and the applicationId is `app.telno`.)

1. **Does inbound really reach the SDK with no webhook and no backend?**
   **[verify]** — first spike in `TODO.md`, on a real device with a real
   Telnyx connection, before any architecture hardens around the answer.
2. **Relationship to Phomo**: successor (Phomo's outbound job moves here and
   Phomo winds down) or sibling experiment? Affects how much of Phomo's
   roadmap (caller-ID presentation) Telno inherits.
3. **Costs on the SDK rate card** for the destinations actually called —
   SDK-originated calls are not billed at trunk rates, and no published
   comparison answers this (`PUSH.md` §10).
