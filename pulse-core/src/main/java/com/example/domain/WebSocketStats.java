package com.example.domain;

/**
 * Snapshot of WebSocket connection counters for one service instance. WebSocket connections are
 * pinned to the instance that accepted them, so these numbers are per instance, not per service.
 */
public record WebSocketStats(
    String instanceId,
    int openConnections,
    int peakOpenConnections,
    long totalOpened,
    long totalClosed,
    long messagesReceived,
    long messagesSent) {}
