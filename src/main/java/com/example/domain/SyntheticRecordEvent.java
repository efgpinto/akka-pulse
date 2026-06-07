package com.example.domain;

import akka.javasdk.annotations.TypeName;
import java.time.Instant;

public sealed interface SyntheticRecordEvent {

  @TypeName("record-created")
  record RecordCreated(String name, String value, Instant createdAt) implements SyntheticRecordEvent {}

  @TypeName("record-updated")
  record RecordUpdated(String value, int version, Instant updatedAt) implements SyntheticRecordEvent {}
}
