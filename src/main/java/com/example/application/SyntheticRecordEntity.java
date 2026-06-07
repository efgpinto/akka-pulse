package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import com.example.domain.SyntheticRecord;
import com.example.domain.SyntheticRecordEvent;
import com.example.domain.SyntheticRecordEvent.RecordCreated;
import com.example.domain.SyntheticRecordEvent.RecordUpdated;

import java.time.Instant;

@Component(id = "synthetic-record")
public class SyntheticRecordEntity extends EventSourcedEntity<SyntheticRecord, SyntheticRecordEvent> {

  private final String entityId;

  public SyntheticRecordEntity(EventSourcedEntityContext context) {
    this.entityId = context.entityId();
  }

  public record CreateCommand(String name, String value, int delaySeconds) {}
  public record UpdateCommand(String value, int delaySeconds) {}

  @Override
  public SyntheticRecord emptyState() {
    return null;
  }

  public Effect<SyntheticRecord> create(CreateCommand command) {
    if (currentState() != null) {
      return effects().error("Record already exists");
    }
    if (command.delaySeconds() < 0 || command.delaySeconds() > 300) {
      return effects().error("delaySeconds must be between 0 and 300");
    }
    if (command.delaySeconds() > 0) {
      sleep(command.delaySeconds());
    }
    var event = new RecordCreated(command.name(), command.value(), Instant.now());
    return effects().persist(event).thenReply(state -> state);
  }

  public Effect<SyntheticRecord> update(UpdateCommand command) {
    if (currentState() == null) {
      return effects().error("Record does not exist");
    }
    if (command.delaySeconds() < 0 || command.delaySeconds() > 300) {
      return effects().error("delaySeconds must be between 0 and 300");
    }
    if (command.delaySeconds() > 0) {
      sleep(command.delaySeconds());
    }
    var event = new RecordUpdated(command.value(), currentState().version() + 1, Instant.now());
    return effects().persist(event).thenReply(state -> state);
  }

  public Effect<SyntheticRecord> get() {
    if (currentState() == null) {
      return effects().error("Record does not exist");
    }
    return effects().reply(currentState());
  }

  @Override
  public SyntheticRecord applyEvent(SyntheticRecordEvent event) {
    return switch (event) {
      case RecordCreated created ->
          SyntheticRecord.create(entityId, created.name(), created.value(), created.createdAt());
      case RecordUpdated updated ->
          currentState().withValue(updated.value(), updated.updatedAt());
    };
  }

  private void sleep(int seconds) {
    try {
      Thread.sleep(seconds * 1000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
