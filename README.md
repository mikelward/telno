# Telno

Telno is a minimalist Android app that makes and receives phone calls on your
Telnyx number — reliable in both directions, with near-zero battery cost while
idle. Outbound calls connect on demand; inbound calls arrive by push, with
Telnyx holding the call while the phone wakes. No persistent background
service and no SIP stack of our own: both legs run on the Telnyx Android
WebRTC SDK. The intended architecture also needs no backend — credential-only
login and webhook-free inbound routing are provider-side — but `SPEC.md`
marks both claims as unverified until the first real-device spikes run.

- **`SPEC.md`** — product and architecture decisions, and the open questions.
- **`TODO.md`** — the phased plan.
- **`AGENTS.md`** — engineering conventions (mirrors sibling repo
  [simmo](https://github.com/mikelward/simmo)).

Sibling projects: [simmo](https://github.com/mikelward/simmo) (per-country SIM
selection for outgoing calls) and [phomo](https://github.com/mikelward/phomo)
(outbound-only SIP client, whose `PUSH.md` analysis led to Telno's
architecture).

Status: project scaffold — a buildable app shell (`./gradlew assembleDebug`,
`test`, `lint`) with a placeholder home screen and CI. The calling
implementation lands per `TODO.md`.
