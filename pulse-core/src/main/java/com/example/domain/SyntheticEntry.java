package com.example.domain;

import java.time.Instant;

public record SyntheticEntry(String entryId, String data, Instant lastUpdated) {

  public SyntheticEntry withData(String newData, Instant updatedAt) {
    return new SyntheticEntry(entryId, newData, updatedAt);
  }
}
