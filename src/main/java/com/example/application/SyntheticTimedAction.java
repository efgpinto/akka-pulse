package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;

@Component(id = "synthetic-timed-action")
public class SyntheticTimedAction extends TimedAction {

  private final ComponentClient componentClient;

  public SyntheticTimedAction(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect execute() {
    componentClient.forKeyValueEntity("timer-counter")
        .method(ConsumerCounterEntity::increment)
        .invoke(new ConsumerCounterEntity.IncrementCommand("timer-fired"));
    return effects().done();
  }
}
