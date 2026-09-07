package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import com.example.domain.SyntheticTopicMessage;

@Component(id = "synthetic-topic-consumer")
@Consume.FromTopic("synthetic-record-events")
public class SyntheticTopicConsumer extends Consumer {

  // Fixed counter id, also used by GET /pulse/topic-counter/{counterId}.
  public static final String COUNTER_ID = "synthetic-record-events";

  private final ComponentClient componentClient;

  public SyntheticTopicConsumer(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onMessage(SyntheticTopicMessage message) {
    componentClient.forKeyValueEntity(COUNTER_ID)
        .method(TopicMessageCounterEntity::increment)
        .invoke(new TopicMessageCounterEntity.IncrementCommand(message.originRegion()));
    return effects().done();
  }
}
