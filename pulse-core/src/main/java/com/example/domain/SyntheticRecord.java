package com.example.domain;

import java.time.Instant;

public record SyntheticRecord(
    String recordId,
    String name,
    String value,
    String status,
    int version,
    Instant lastUpdated) {

  public SyntheticRecord withValue(String newValue, Instant updatedAt) {
    return new SyntheticRecord(recordId, name, newValue, "UPDATED", version + 1, updatedAt);
  }

  public static SyntheticRecord create(String recordId, String name, String value, Instant createdAt) {
    return new SyntheticRecord(recordId, name, value, "CREATED", 1, createdAt);
  }
}
