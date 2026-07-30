package ir.codelighthouse.bazikhooneh.accessibility;

import android.app.Activity;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

public final class FocusStableAnnouncer {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private TextView source;
  private String pending = "";
  private boolean waitingForFocus, focusSettled;
  private final Runnable speak =
      () -> {
        String value = pending;
        pending = "";
        waitingForFocus = false;
        focusSettled = false;
        if (!value.isEmpty()) source.announceForAccessibility(value);
      };

  public FocusStableAnnouncer(Activity activity, TextView source) {
    this.source = source;
    View root = activity.findViewById(android.R.id.content);
    root.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public boolean onRequestSendAccessibilityEvent(
              ViewGroup host, View child, AccessibilityEvent event) {
            boolean result = super.onRequestSendAccessibilityEvent(host, child, event);
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
              focusArrived();
            return result;
          }
        });
  }

  public void beforeFocusedViewIsRemoved() {
    waitingForFocus = true;
    focusSettled = false;
    handler.removeCallbacks(speak);
  }

  public void announce(String text) {
    if (text == null || text.isEmpty()) return;
    if (!pending.isEmpty()) pending += ". ";
    pending += text;
    handler.removeCallbacks(speak);
    if (waitingForFocus) {
      if (focusSettled) handler.postDelayed(speak, 80);
      else handler.postDelayed(speak, 2000);
    } else handler.postDelayed(speak, 250);
  }

  private void focusArrived() {
    if (!waitingForFocus) return;
    focusSettled = true;
    handler.removeCallbacks(speak);
    if (!pending.isEmpty()) handler.postDelayed(speak, 80);
  }

  public void close() {
    handler.removeCallbacksAndMessages(null);
    pending = "";
  }
}
