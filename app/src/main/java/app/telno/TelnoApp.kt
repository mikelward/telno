package app.telno

import android.app.Application

/**
 * Nothing runs at process start: the battery model (SPEC "Battery model") keeps
 * Telno idle until a call starts or a push arrives, so this class stays empty
 * until something genuinely belongs at application scope (the telemetry opt-in
 * wiring, once settings exist).
 */
class TelnoApp : Application()
