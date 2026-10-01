package com.example.domain;

import java.time.Instant;

/** Counts how often a probe side effect ran, with the message metadata seen on the last run. */
public record ProbeLedger(
    String ledgerId,
    long count,
    String lastOriginRegion,
    boolean lastHasLocalOrigin,
    String lastSelfRegion,
    Instant lastAt) {

  public ProbeLedger record(String originRegion, boolean hasLocalOrigin, String selfRegion, Instant at) {
    return new ProbeLedger(ledgerId, count + 1, originRegion, hasLocalOrigin, selfRegion, at);
  }

  public static ProbeLedger empty(String ledgerId) {
    return new ProbeLedger(ledgerId, 0, "", false, "", null);
  }
}
