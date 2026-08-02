package ir.codelighthouse.bazikhooneh.core.audio;

import android.media.AudioManager;
import android.media.ToneGenerator;

/** Plays short, unobtrusive chat cues without requiring media playback permissions. */
public final class MessageSoundPlayer {
  private static final int VOLUME_PERCENT = 55;
  private final ToneGenerator tones =
      new ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME_PERCENT);

  public void sent() {
    tones.startTone(ToneGenerator.TONE_PROP_ACK, 90);
  }

  public void received() {
    tones.startTone(ToneGenerator.TONE_PROP_PROMPT, 120);
  }

  public void release() {
    tones.release();
  }
}
