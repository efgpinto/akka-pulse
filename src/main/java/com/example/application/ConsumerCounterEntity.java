package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import com.example.domain.ConsumerCounter;

import java.time.Instant;

@Component(id = "consumer-counter")
public class ConsumerCounterEntity extends KeyValueEntity<ConsumerCounter> {

  public record IncrementCommand(String eventType) {}

  public Effect<ConsumerCounter> increment(IncrementCommand command) {
    var state = currentState() != null
        ? currentState()
        : ConsumerCounter.empty(commandContext().entityId());
    var updated = state.increment(command.eventType(), Instant.now());
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<ConsumerCounter> get() {
    if (currentState() == null) {
      return effects().reply(ConsumerCounter.empty(commandContext().entityId()));
    }
    return effects().reply(currentState());
  }
}
