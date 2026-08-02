# Telno privacy policy

Telno is an Android app that makes and receives phone calls on your own
Telnyx number. This policy describes what data the app handles and where it
goes. Telno is under active development; this policy describes the app as
designed and is updated before any behavior that touches new data ships.

## The short version

Telno runs no servers of its own. Today your data goes to two parties, both
of which you have a direct relationship with: **Telnyx** (your calling
provider) and **Google** (push notifications, and crash reporting if you opt
in). Telno's developer receives nothing unless you explicitly opt in to
crash reporting or usage analytics, or send something yourself. If a future
feature changes any of this, this policy changes first.

## What the app stores on your device

- **Your Telnyx SIP connection credentials**, which you enter during setup.
  They are stored encrypted on your device and excluded from device backups,
  so Google's backup service does not carry them off the device.
- **Settings** (ordinary preferences). These may be included in your device's
  normal Android backup. A future version may offer to back up more of your
  data; anything beyond today's behavior would be your choice to enable.
- **A diagnostic log** kept on the device, so that a failed call or a phone
  that didn't ring can be diagnosed. It records coarse call and reachability
  state — call direction, call state changes, error codes, a dialed number's
  country calling code, and push-registration health events. It never records
  a full phone number, a contact's name or number, your credentials, or a raw
  push token. The log stays on the device unless you choose to share it — a
  future version may offer to include it (or a redacted summary) in crash
  reports you have opted into.

Telno keeps no call history of its own beyond what Android itself records
for calls.

## What leaves the device, and to whom

- **Telnyx** carries your calls. Placing or receiving a call necessarily
  sends Telnyx the numbers involved, your credentials to sign in, and the
  call audio, under [Telnyx's privacy policy](https://telnyx.com/privacy-policy).
  Telno adds nothing beyond what making the call requires.
- **Google (Firebase Cloud Messaging)** delivers the push notification that
  wakes the app for each incoming call. Google necessarily sees that a push
  was sent to your device; the push payload identifies the call, not your
  contacts.
- **Google (Firebase Crashlytics and Analytics), only if you opt in.** Crash
  reporting and anonymous usage statistics are off by default and stay off
  until you explicitly enable them in the app. No advertising identifier is
  collected either way.

Telno shows no ads and contains no ad SDKs. The Firebase Crashlytics and
Analytics libraries above are compiled into the app but collect nothing
unless you explicitly opt in. Nothing is sold to anyone.

## Permissions

Telno asks for permissions when a feature first needs them, not up front:
network access (install-time), microphone (first call), calling integration
(so Android treats Telno calls like phone calls), notifications (missed
calls and reachability warnings), and Bluetooth (first headset use). Denying
a permission degrades the related feature and nothing else.

## Contact

Questions about this policy: open an issue at
<https://github.com/mikelward/telno/issues>.
