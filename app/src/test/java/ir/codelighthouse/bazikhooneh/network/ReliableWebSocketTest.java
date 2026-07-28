package ir.codelighthouse.bazikhooneh.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReliableWebSocketTest {
    @Test public void reconnectDelayUsesExponentialBackoffAndBoundedJitter() {
        assertEquals(750L, ReliableWebSocket.reconnectDelayMillis(0, 0.0));
        assertEquals(1250L, ReliableWebSocket.reconnectDelayMillis(0, 1.0));
        assertEquals(3000L, ReliableWebSocket.reconnectDelayMillis(2, 0.0));
        assertEquals(5000L, ReliableWebSocket.reconnectDelayMillis(2, 1.0));
    }

    @Test public void reconnectDelayNeverExceedsThirtySeconds() {
        assertEquals(30000L, ReliableWebSocket.reconnectDelayMillis(20, 1.0));
        assertTrue(ReliableWebSocket.reconnectDelayMillis(20, 0.0) >= 500L);
    }
}
