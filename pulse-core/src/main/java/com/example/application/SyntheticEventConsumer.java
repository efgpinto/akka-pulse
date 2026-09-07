package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import com.example.domain.SyntheticRecordEvent;
import com.example.domain.SyntheticRecordEvent.RecordCreated;
import com.example.domain.SyntheticRecordEvent.RecordUpdated;

@Component(id = "synthetic-event-consumer")
@Consume.FromEventSourcedEntity(SyntheticRecordEntity.class)
public class SyntheticEventConsumer extends Consumer {

  private final ComponentClient componentClient;

  public SyntheticEventConsumer(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onEvent(SyntheticRecordEvent event) {
    String eventType = switch (event) {
      case RecordCreated ignored -> "record-created";
      case RecordUpdated ignored -> "record-updated";
    };

    componentClient.forKeyValueEntity("synthetic-record-counter")
        .method(ConsumerCounterEntity::increment)
        .invoke(new ConsumerCounterEntity.IncrementCommand(eventType));

    return effects().done();
  }
}
