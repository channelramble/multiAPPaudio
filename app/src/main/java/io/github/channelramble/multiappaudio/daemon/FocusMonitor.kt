package io.github.channelramble.multiappaudio.daemon

import android.content.Context
import android.media.AudioFocusInfo
import android.media.AudioManager
import android.media.audiopolicy.AudioPolicy
import android.os.Handler

/**
 * Registers an AudioPolicy with a focus *listener* (a "focus follower"). This is read-only: it
 * does not make us the focus policy and does not change any focus decision. It lets us see every
 * grant, loss and abandon that AudioService handles itself (i.e. whenever no external focus policy,
 * such as Android Auto's, is installed).
 */
class FocusMonitor(
    private val context: Context,
    private val handler: Handler,
    private val sink: Sink,
) {
    interface Sink {
        fun onFocusGrant(afi: AudioFocusInfo)
        fun onFocusLoss(afi: AudioFocusInfo, wasNotified: Boolean)
    }

    private var policy: AudioPolicy? = null

    val isRunning: Boolean get() = policy != null

    fun start(log: EventLog): Boolean {
        if (policy != null) return true
        return try {
            val listener = object : AudioPolicy.AudioPolicyFocusListener() {
                override fun onAudioFocusGrant(afi: AudioFocusInfo, requestResult: Int) {
                    log.guard("focus grant") { sink.onFocusGrant(afi) }
                }

                override fun onAudioFocusLoss(afi: AudioFocusInfo, wasNotified: Boolean) {
                    log.guard("focus loss") { sink.onFocusLoss(afi, wasNotified) }
                }
            }
            // setAudioPolicyFocusListener() returns void in the framework, so no chaining here.
            val builder = AudioPolicy.Builder(context).setLooper(handler.looper)
            builder.setAudioPolicyFocusListener(listener)
            val built = builder.build()
            val am = context.getSystemService(AudioManager::class.java)
            val result = Hidden.call(am, "registerAudioPolicy", built) as Int
            if (result == 0) {
                policy = built
                log.add("focus", "focus follower registered")
                true
            } else {
                log.add("focus", "registerAudioPolicy returned $result")
                false
            }
        } catch (t: Throwable) {
            log.add("focus", "focus follower unavailable: $t")
            false
        }
    }

    fun stop() {
        val p = policy ?: return
        policy = null
        runCatching {
            val am = context.getSystemService(AudioManager::class.java)
            Hidden.call(am, "unregisterAudioPolicy", p)
        }
    }

    companion object {
        fun describe(afi: AudioFocusInfo): String =
            "${afi.packageName} uid=${afi.clientUid} gain=${gainName(afi.gainRequest)}" +
                (if (afi.lossReceived != 0) " loss=${lossName(afi.lossReceived)}" else "") +
                (if (afi.flags != 0) " flags=0x${Integer.toHexString(afi.flags)}" else "") +
                " usage=${afi.attributes?.usage}"

        fun gainName(gain: Int) = when (gain) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "GAIN_TRANSIENT"
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "GAIN_TRANSIENT_MAY_DUCK"
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "GAIN_TRANSIENT_EXCLUSIVE"
            else -> gain.toString()
        }

        fun lossName(loss: Int) = when (loss) {
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            else -> loss.toString()
        }
    }
}
