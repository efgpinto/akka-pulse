package com.example.application;

import com.example.domain.WebSocketStats;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory WebSocket connection counters for this service instance. Not an Akka component: a
 * WebSocket connection lives on the instance that accepted it, so a per-instance counter is the
 * honest measure. The instance id lets a client attribute stats to a specific replica.
 */
public class WebSocketConnectionTracker {

  private final String instanceId;
  private final AtomicInteger open = new AtomicInteger();
  private final AtomicInteger peak = new AtomicInteger();
  private final AtomicLong totalOpened = new AtomicLong();
  private final AtomicLong totalClosed = new AtomicLong();
  private final AtomicLong messagesReceived = new AtomicLong();
  private final AtomicLong messagesSent = new AtomicLong();

  public WebSocketConnectionTracker(String instanceId) {
    this.instanceId = instanceId;
  }

  /** Identifies the instance by hostname (the pod name when deployed), else a random id. */
  public static WebSocketConnectionTracker forThisInstance() {
    String id;
    try {
      id = InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      id = UUID.randomUUID().toString();
    }
    return new WebSocketConnectionTracker(id);
  }

  public void connectionOpened() {
    var now = open.incrementAndGet();
    totalOpened.incrementAndGet();
    peak.accumulateAndGet(now, Math::max);
  }

  public void connectionClosed() {
    open.decrementAndGet();
    totalClosed.incrementAndGet();
  }

  public void messageReceived() {
    messagesReceived.incrementAndGet();
  }

  public void messageSent() {
    messagesSent.incrementAndGet();
  }

  public int openConnections() {
    return open.get();
  }

  /** Clears peak and totals. Currently open connections stay counted. */
  public void reset() {
    peak.set(open.get());
    totalOpened.set(0);
    totalClosed.set(0);
    messagesReceived.set(0);
    messagesSent.set(0);
  }

  public WebSocketStats snapshot() {
    return new WebSocketStats(instanceId, open.get(), peak.get(), totalOpened.get(),
        totalClosed.get(), messagesReceived.get(), messagesSent.get());
  }
}
