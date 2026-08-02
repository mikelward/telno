# Telno — phased plan

Phases are ordered so that every phase ends with something verifiable, and the
riskiest unverified claims (see `SPEC.md` **[verify]** markers) are retired
first. Real-device checks are called out explicitly because the sandbox can
verify none of the call or push paths.

## Phase 0 — Answers before architecture

The device spikes below deliberately run *before* Telno has a scaffold, so a
negative answer can reshape the architecture before anything hardens. They
therefore don't build on this repo: use the Telnyx Android SDK's own
quickstart/sample app (or a disposable spike harness kept out of this repo)
as the vehicle, and record only the findings here.

- [x] ~~Primary line vs. secondary number~~ — answered: secondary line
      (recorded in `SPEC.md` Product shape, with routing owned by Simmo and
      the hand-off intent as the integration surface; dialer/router roles
      deferred to a possible v2).
- [x] ~~App identity~~ — answered: applicationId `app.telno`, display name
      Telno (recorded in `SPEC.md` Distribution and versioning); the
      Pixel/Samsung, Android 16+/`minSdk 34` posture stands unless revisited.
- [ ] **Spike (real device): Telnyx credential login + one outbound call** via
      the SDK quickstart shape — confirms credentials-only auth, and lets us
      hear the audio quality `PUSH.md` says only a real call can judge.
- [ ] **Spike (real device): inbound call → FCM push → ring, with no backend**
      — verifies the backend-free claim (open question 1). Record the answer
      (and any required provider-side TeXML/webhook config) in `SPEC.md`.
- [ ] Record SDK-rate-card cost for the real destination mix (open question 3).

## Phase 1 — Project scaffold

- [x] Gradle scaffold: single `:app` module, Kotlin, Compose, Material 3,
      version scheme from `SPEC.md` (rev-list count), build-identity suffixes
      following Simmo (icons are color-coded per variant for now — see
      Decisions needing review).
- [x] `.claude/hooks/session-start.sh` mirroring Simmo's, so remote sandboxes
      can provision the Android SDK and run `test`/`lint`.
- [x] CI (`.github/workflows/android-ci.yml`): build, unit tests, lint, and
      screenshot recording (lean validation CI — the release pipeline is
      deferred; see Decisions needing review). The Play upload step, when it
      lands, ships together with the already-written `docs/PRIVACY.md`.
- [x] `docs/PRIVACY.md` (backs the hosted privacy policy; user-facing per
      `AGENTS.md`), covering Telnyx, FCM, and the on-device debug log.
- [x] Firebase dependencies declared; telemetry gated on an un-checked-in
      `google-services.json` (collection off in the manifest until an in-app
      opt-in exists).
- [ ] Telnyx SDK dependency — deferred to Phase 2; see Decisions needing
      review (the SDK is JitPack-distributed and JitPack is unreachable from
      the sandbox, so declaring it now would break every sandbox build).
- [ ] Release pipeline (Firebase App Distribution, Play internal track,
      "What's new" from commit subjects, screenshot drift auto-commit),
      mirroring Simmo's — when there is something worth distributing.

## Phase 2 — Outbound calling

- [x] Encrypted, backup-excluded credential store + setup screen (Keystore
      AES-GCM mirroring Phomo's store; home reachability now derives from the
      stored account — credentials without a push binding read "can't receive
      calls", honestly).
- [ ] Pure-Kotlin call state machine (outbound rows) + exhaustive unit tests.
- [ ] Self-managed `ConnectionService` + phone account registration.
- [ ] Dialer screen (E.164 normalization, emergency-call refusal with
      hand-off to the platform dialer) and in-call screen (mute, speaker,
      DTMF, hangup).
- [ ] Simmo hand-off intent (`SPEC.md` "Simmo integration"): handle
      `ACTION_CALL` `tel:` by placing the call immediately (no confirmation,
      no contact entry), gated on the caller holding `CALL_PHONE`;
      `ACTION_DIAL` prefill honored too; emergency numbers refused loudly;
      unit tests for the gate and refusal paths. The Simmo-side action is
      Simmo-repo work.
- [ ] In-call foreground service scoped to the call's lifetime; full teardown
      on hangup verified (nothing survives the call).
- [ ] In-call proximity handling: declare `WAKE_LOCK` and hold the platform
      proximity wakelock on earpiece routes so the screen turns off against
      the face — self-managed calls draw their own in-call UI, so the system
      dialer's handling never applies (see `SPEC.md` Telecom integration).
- [ ] Minimal sanitized debug event log (call direction, state transitions,
      error codes — per `AGENTS.md` Privacy), so Phase 3's wake-to-ring QA
      can tell a stale token from a login failure from a late push. The
      in-app reader and quality metrics land in Phase 4.
- [ ] Screenshot tests for every screen state; device QA: real call placed,
      audio both ways, Bluetooth route, proximity screen-off on earpiece,
      hand-off intent places a call immediately, teardown confirmed.

## Phase 3 — Inbound calling

- [ ] `FirebaseMessagingService` + push-payload handling; state machine gains
      the inbound rows (all races in `SPEC.md` "Call state machine").
- [ ] Protect the call attempt from push receipt through ringing or a
      terminal visible failure, time-bounded and torn down on every exit —
      an unprotected process can be killed mid-login and drop the call
      before it rings (`SPEC.md` Battery model fixes the window and the
      outcomes; the mechanism is this task's choice). Device QA includes the
      kill-during-login gap.
- [ ] Token binding lifecycle: bind at login, re-bind on `onNewToken` and on
      credential change; retry a re-bind that failed offline (sanctioned
      background work per the battery model); every failed attempt —
      rejected online, or retries exhausted — durably marks reachability
      unhealthy with a sanitized reason and surfaces it (notification or the
      permission-independent fallback); unit tests for each trigger and each
      terminal failure.
- [ ] Incoming-call UI: CallStyle notification + full-screen intent, answer /
      decline; missed-call notification on every failure path, plus the
      permission-independent fallback (durable failure record + unhealthy
      reachability state surfaced on next app open) for when
      `POST_NOTIFICATIONS` is denied.
- [ ] Process-death recovery: an inbound attempt the process died in the
      middle of becomes a missed-call record, unhealthy reachability, and a
      debug-log reason by the next app run (`SPEC.md` "Call state machine");
      tested by killing the process between push and ring, not only as a
      state machine row.
- [ ] Reachability indicator on the home screen, with opportunistic
      confirmation (app open, post-call, post-rotation) — no background jobs.
- [ ] Device QA: wake-to-ring latency on Dozing Pixel and Samsung, push after
      hangup, push during cellular call, cellular call during Telnyx call.

## Phase 4 — Hardening and polish

- [ ] In-app reader for the debug event log (the log itself lands in
      Phase 2).
- [ ] Surface SDK call-quality metrics in the debug log.
- [ ] Multi-week reachability observation on a real device (FCM priority
      downgrade, OEM deferral); decide then whether a battery-exemption
      prompt is warranted (recorded decision in `SPEC.md`).
- [ ] Revisit deferred open question: relationship to Phomo (question 2).

## V2 / roadmap (not MVP)

- [ ] **Acting as a dialer or call router** (owner decision, deferred from
      MVP): explore taking the dialer role and/or a `CallRedirectionService`
      so Telno could carry or route native-dialer calls itself. For MVP,
      Simmo owns routing and Telno's only entry points are its own UI and
      the hand-off intent; v1 must not preclude this, but must not build
      toward it either.
- [ ] **Translations** (owner decision, after MVP): English-only until the
      MVP ships. New base strings still land with the per-string
      `MissingTranslation` markers per `AGENTS.md`, so the follow-up
      translation PR has a greppable worklist when the time comes — but no
      locale directories and no translation PRs before then.

## Decisions needing review

(Autopilot guesses land here — what was decided, the alternative, and why it
is reversible.)

- **Telnyx SDK dependency deferred to Phase 2.** The SDK is distributed via
  JitPack, which this sandbox's egress policy blocks (Maven Central carries
  only Telnyx's low-level `com.telnyx.webrtc.lib:library`, not the SDK), so
  declaring it now would make every sandbox `./gradlew build` fail.
  Alternative: declare it anyway and accept a broken sandbox baseline.
  Reversible: one version-catalog entry plus a JitPack repository line when
  Phase 2 starts; revisit alongside the Phase 0 spike, which may also settle
  whether the Maven Central lib + thin glue is the better route.
- **Lean CI now, release pipeline later.** The scaffold ships build + unit
  tests + lint + screenshot recording only; Firebase App Distribution, the
  Play internal track, release notes from commit subjects, and snapshot drift
  auto-commit follow when there is something to distribute (owner: "set up CI
  when the time is right"). Alternative: port Simmo's full pipeline now.
  Reversible: additive workflow changes.
- **Variant icons are color-coded, not letter-badged.** Play build green, CI
  tester slate, local dev amber, same placeholder "T" mark — enough to tell
  three side-by-side installs apart, without porting Simmo's badge-bar vector
  geometry for builds nobody distributes yet. Alternative: Simmo's lettered
  DEBUG/DEV bars. Reversible: swap the drawables when real branding lands.
- **Screenshot CI records and uploads; it does not verify.** Pixel-exact
  verify across environments is the flaky-CI trap Simmo solved with drift
  auto-commit; that machinery arrives with the release pipeline. Alternative:
  verify mode now. Reversible: flip the flag once auto-commit exists.
