package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import com.example.domain.HealthCheckEntry;

import java.time.Instant;

@Component(id = "health-check")
public class HealthCheckEntity extends KeyValueEntity<HealthCheckEntry> {

  public Effect<Done> set() {
    var region = commandContext().selfRegion();
    var entry = new HealthCheckEntry(Instant.now(), "OK", region);
    return effects().updateState(entry).thenReply(Done.getInstance());
  }

  public Effect<HealthCheckEntry> get() {
    if (currentState() == null) {
      return effects().error("No heartbeat recorded");
    }
    return effects().reply(currentState());
  }
}
