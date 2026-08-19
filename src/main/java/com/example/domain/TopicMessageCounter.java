package com.example.domain;

import java.time.Instant;

public record TopicMessageCounter(
    String counterId,
    long messageCount,
    String lastOriginRegion,
    Instant lastMessageAt) {

  public TopicMessageCounter increment(String originRegion, Instant at) {
    return new TopicMessageCounter(counterId, messageCount + 1, originRegion, at);
  }

  public static TopicMessageCounter empty(String counterId) {
    return new TopicMessageCounter(counterId, 0, "", null);
  }
}
