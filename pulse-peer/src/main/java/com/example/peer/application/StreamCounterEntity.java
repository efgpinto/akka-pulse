package com.example.peer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import com.example.peer.domain.StreamCounter;

import java.time.Instant;

@Component(id = "stream-counter")
public class StreamCounterEntity extends KeyValueEntity<StreamCounter> {

  public Effect<StreamCounter> increment() {
    var state = currentState() != null ? currentState() : StreamCounter.empty();
    var updated = state.increment(Instant.now());
    return effects().updateState(updated).thenReply(updated);
  }

  public Effect<StreamCounter> get() {
    if (currentState() == null) {
      return effects().reply(StreamCounter.empty());
    }
    return effects().reply(currentState());
  }
}
