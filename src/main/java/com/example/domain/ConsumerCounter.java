package com.example.domain;

import java.time.Instant;

public record ConsumerCounter(
    String counterId,
    long eventCount,
    String lastEventType,
    Instant lastEventAt) {

  public ConsumerCounter increment(String eventType, Instant eventTime) {
    return new ConsumerCounter(counterId, eventCount + 1, eventType, eventTime);
  }

  public static ConsumerCounter empty(String counterId) {
    return new ConsumerCounter(counterId, 0, "", null);
  }
}
