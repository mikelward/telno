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

- [ ] Gradle scaffold: single `:app` module, Kotlin, Compose, Material 3,
      version scheme from `SPEC.md` (rev-list count), build-identity suffixes
      and badged icons following Simmo.
- [ ] `.claude/hooks/session-start.sh` mirroring Simmo's, so remote sandboxes
      can provision the Android SDK and run `test`/`lint`.
- [ ] CI (`.github/workflows/android-ci.yml`): build, unit tests, lint,
      screenshot recording with drift auto-commit; release steps secret-gated.
      **The Play upload step stays disabled until `docs/PRIVACY.md` exists** —
      Phase 2 builds already store credentials and place real calls, so the
      policy cannot wait for the polish phase.
- [ ] `docs/PRIVACY.md` (backs the hosted privacy policy; user-facing per
      `AGENTS.md`), covering Telnyx, FCM, and the on-device debug log.
- [ ] Telnyx SDK + Firebase dependencies declared; telemetry gated on an
      un-checked-in `google-services.json`.

## Phase 2 — Outbound calling

- [ ] Encrypted, backup-excluded credential store + setup screen.
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

## Decisions needing review

(Autopilot guesses land here — what was decided, the alternative, and why it
is reversible. Empty so far; the skeleton itself made one, recorded in the
opening PR: bootstrapping `main` with an empty commit so the PR had a base.
The device posture copied from Phomo was reviewed with the `app.telno`
identity decision and stands.)
