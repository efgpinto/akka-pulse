package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import com.example.domain.SyntheticEntry;

import java.time.Instant;

@Component(id = "synthetic-entry")
public class SyntheticEntryEntity extends KeyValueEntity<SyntheticEntry> {

  public record SetCommand(String data, int delaySeconds) {}

  public Effect<SyntheticEntry> set(SetCommand command) {
    if (command.delaySeconds() < 0 || command.delaySeconds() > 300) {
      return effects().error("delaySeconds must be between 0 and 300");
    }
    if (command.delaySeconds() > 0) {
      sleep(command.delaySeconds());
    }
    var entry = new SyntheticEntry(commandContext().entityId(), command.data(), Instant.now());
    return effects().updateState(entry).thenReply(entry);
  }

  public Effect<SyntheticEntry> get() {
    if (currentState() == null) {
      return effects().error("Entry does not exist");
    }
    return effects().reply(currentState());
  }

  public Effect<Done> delete() {
    return effects().deleteEntity().thenReply(Done.getInstance());
  }

  private void sleep(int seconds) {
    try {
      Thread.sleep(seconds * 1000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
