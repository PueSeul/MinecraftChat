package com.dudal.javachat.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dudal.javachat.protocol.ConnectionState;

import org.junit.Test;

public final class ConnectionLifecyclePolicyTest {
    @Test
    public void terminalStatesStopTheService() {
        assertTrue(ConnectionLifecyclePolicy.shouldStopService(ConnectionState.DISCONNECTED));
        assertTrue(ConnectionLifecyclePolicy.shouldStopService(ConnectionState.ERROR));
    }

    @Test
    public void activeStatesKeepTheServiceRunning() {
        assertFalse(ConnectionLifecyclePolicy.shouldStopService(ConnectionState.CONNECTING));
        assertFalse(ConnectionLifecyclePolicy.shouldStopService(ConnectionState.AUTHENTICATING));
        assertFalse(ConnectionLifecyclePolicy.shouldStopService(ConnectionState.CONNECTED));
    }
}
