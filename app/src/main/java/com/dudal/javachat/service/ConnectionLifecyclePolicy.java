package com.dudal.javachat.service;

import com.dudal.javachat.protocol.ConnectionState;

final class ConnectionLifecyclePolicy {
    private ConnectionLifecyclePolicy() {
    }

    static boolean shouldStopService(ConnectionState state) {
        return state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR;
    }
}
