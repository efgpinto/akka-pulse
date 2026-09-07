package com.example.common.domain;

import akka.javasdk.annotations.TypeName;
import java.time.Instant;

/**
 * Public event type of the cross-service stream "synthetic-records" produced by pulse-core.
 * A deliberate copy of the internal SyntheticRecordEvent shape: the internal events can evolve
 * without breaking downstream consumers of this public contract.
 */
public sealed interface PulseStreamEvent {

  @TypeName("pulse-record-created")
  record RecordCreated(String name, String value, Instant createdAt) implements PulseStreamEvent {}

  @TypeName("pulse-record-updated")
  record RecordUpdated(String value, int version, Instant updatedAt) implements PulseStreamEvent {}
}
