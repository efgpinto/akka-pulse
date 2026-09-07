package com.example.peer.domain;

import java.time.Instant;

/** Count of events consumed from pulse-core's "synthetic-records" stream. */
public record StreamCounter(long count, Instant lastEventAt) {

  public static StreamCounter empty() {
    return new StreamCounter(0, null);
  }

  public StreamCounter increment(Instant at) {
    return new StreamCounter(count + 1, at);
  }
}
