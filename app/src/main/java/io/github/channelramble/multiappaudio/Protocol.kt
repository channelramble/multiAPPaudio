package io.github.channelramble.multiappaudio

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/**
 * Wire protocol between the app process and the shell-uid helper daemon.
 *
 * The daemon is a plain [android.os.Binder] started by Shizuku as a "user service". We use a tiny
 * hand-written protocol (Bundles in, Bundles out) instead of AIDL so the daemon can evolve without
 * regenerating stubs, and so older app/daemon pairs fail soft.
 */
object Protocol {
    const val DESCRIPTOR = "io.github.channelramble.multiappaudio.IHelperDaemon"

    const val TX_STATUS = IBinder.FIRST_CALL_TRANSACTION
    const val TX_APPLY_CONFIG = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TX_ACTION = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TX_LOG = IBinder.FIRST_CALL_TRANSACTION + 3

    /** Shizuku's reserved transaction code for UserService#destroy. */
    const val TX_SHIZUKU_DESTROY = 16777115

    // ---- config keys (app -> daemon) ----
    const val C_RESTORE_HELPER = "restore_helper"
    const val C_PASS_THROUGH_PKGS = "pass_through_pkgs"
    const val C_AA_ISOLATION = "aa_isolation"
    const val C_ISOLATION_ALWAYS = "isolation_always"
    const val C_AA_GUARD = "aa_guard"
    const val C_GUARD_WINDOW_S = "guard_window_s"
    const val C_PAUSE_ON_CALL = "pause_on_call"

    // ---- actions ----
    const val A_SET_MULTI_FOCUS = "set_multi_focus"
    const val A_SET_FIX_FLAG = "set_fix_flag"
    const val A_APPOPS_SET = "appops_set"
    const val A_APPOPS_GET = "appops_get"
    const val A_SNAPSHOT = "snapshot"
    const val A_SIMULATE_PROJECTION = "simulate_projection"
    const val A_CLEAR_LOG = "clear_log"

    // ---- action args / results ----
    const val K_ENABLED = "enabled"
    const val K_PACKAGE = "package"
    const val K_PACKAGES = "packages"
    const val K_MODE = "mode"
    const val K_MODES = "modes"
    const val K_OK = "ok"
    const val K_MESSAGE = "message"
    const val K_TEXT = "text"

    // ---- status keys (daemon -> app) ----
    const val S_DAEMON_VERSION = "daemon_version"
    const val S_UID = "uid"
    const val S_SDK = "sdk"
    const val S_MULTI_FOCUS = "multi_focus"             // "true" / "false" / "unknown"
    const val S_EXT_FOCUS_POLICY = "ext_focus_policy"   // "true" / "false" / "unknown"
    const val S_FIX_FLAG = "fix_flag"                   // "true" / "false" / "unknown"
    const val S_FIX_FLAG_STAGED = "fix_flag_staged"     // pending DeviceConfig value, may be null
    const val S_ISOLATION_SUPPORTED = "isolation_supported"
    const val S_FOCUS_MONITOR = "focus_monitor"
    const val S_PROJECTING = "projecting"
    const val S_PROJECTION_SOURCE = "projection_source"
    const val S_SIMULATED = "simulated"
    const val S_ISOLATED = "isolated"
    const val S_SESSIONS = "sessions"
    const val S_AUDIO_MODE = "audio_mode"
    const val S_RESTORES = "restores"
    const val S_GUARD_FIXES = "guard_fixes"

    const val DAEMON_VERSION = 1
}

/** Client-side proxy for the daemon binder. All calls are synchronous and may throw. */
class DaemonClient(val binder: IBinder) {

    val isAlive: Boolean get() = binder.pingBinder()

    fun status(): Bundle = call(Protocol.TX_STATUS, null)

    fun applyConfig(config: Bundle) {
        call(Protocol.TX_APPLY_CONFIG, config)
    }

    fun action(name: String, args: Bundle = Bundle()): Bundle {
        val payload = Bundle(args)
        payload.putString("__action", name)
        return call(Protocol.TX_ACTION, payload)
    }

    fun log(): String = call(Protocol.TX_LOG, null).getString(Protocol.K_TEXT).orEmpty()

    private fun call(code: Int, arg: Bundle?): Bundle {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(Protocol.DESCRIPTOR)
            data.writeBundle(arg ?: Bundle())
            if (!binder.transact(code, data, reply, 0)) {
                throw RemoteException("daemon rejected transaction $code")
            }
            reply.readException()
            return reply.readBundle(javaClass.classLoader) ?: Bundle()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
