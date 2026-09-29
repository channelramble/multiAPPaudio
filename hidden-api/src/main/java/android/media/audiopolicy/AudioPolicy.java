package android.media.audiopolicy;

import android.content.Context;
import android.media.AudioFocusInfo;
import android.os.Looper;

/**
 * Compile-time stub of the @SystemApi class {@code android.media.audiopolicy.AudioPolicy}.
 * Only the members used by the helper daemon are declared. The real implementation is provided
 * by the device framework at runtime.
 */
@SuppressWarnings("unused")
public class AudioPolicy {
    private AudioPolicy() {
        throw new RuntimeException("Stub!");
    }

    public abstract static class AudioPolicyFocusListener {
        public AudioPolicyFocusListener() {}

        public void onAudioFocusGrant(AudioFocusInfo afi, int requestResult) {}

        public void onAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {}

        public void onAudioFocusRequest(AudioFocusInfo afi, int requestResult) {}

        public void onAudioFocusAbandon(AudioFocusInfo afi) {}
    }

    public static class Builder {
        public Builder(Context context) {
            throw new RuntimeException("Stub!");
        }

        public Builder setLooper(Looper looper) throws IllegalArgumentException {
            throw new RuntimeException("Stub!");
        }

        /** Note: returns void in the real framework class, so it cannot be chained. */
        public void setAudioPolicyFocusListener(AudioPolicyFocusListener l) {
            throw new RuntimeException("Stub!");
        }

        public AudioPolicy build() { throw new RuntimeException("Stub!"); }
    }
}
