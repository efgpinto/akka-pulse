package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import com.example.domain.TopicMessageCounter;

import java.time.Instant;

@Component(id = "topic-message-counter")
public class TopicMessageCounterEntity extends KeyValueEntity<TopicMessageCounter> {

  public record IncrementCommand(String originRegion) {}

  public Effect<TopicMessageCounter> increment(IncrementCommand command) {
    var state = currentState() != null
        ? currentState()
        : TopicMessageCounter.empty(commandContext().entityId());
    var updated = state.increment(command.originRegion(), Instant.now());
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<TopicMessageCounter> get() {
    if (currentState() == null) {
      return effects().reply(TopicMessageCounter.empty(commandContext().entityId()));
    }
    return effects().reply(currentState());
  }
}
