package com.example.peer.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import com.example.common.domain.PulseStreamEvent;

/**
 * Consumes pulse-core's cross-service stream and counts what arrives. The stream probe endpoint
 * reads the counter to prove service-to-service eventing works in this environment.
 */
@Component(id = "stream-probe-consumer")
@Consume.FromServiceStream(service = "pulse-core", id = "synthetic-records")
public class StreamProbeConsumer extends Consumer {

  private final ComponentClient componentClient;

  public StreamProbeConsumer(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onEvent(PulseStreamEvent event) {
    componentClient.forKeyValueEntity("synthetic-records")
        .method(StreamCounterEntity::increment)
        .invoke();
    return effects().done();
  }
}
