package com.example.application;

import akka.javasdk.Metadata;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Produce;
import akka.javasdk.consumer.Consumer;
import com.example.domain.SyntheticRecordEvent;
import com.example.domain.SyntheticRecordEvent.RecordCreated;
import com.example.domain.SyntheticRecordEvent.RecordUpdated;
import com.example.domain.SyntheticTopicMessage;

@Component(id = "synthetic-topic-producer")
@Consume.FromEventSourcedEntity(SyntheticRecordEntity.class)
@Produce.ToTopic("synthetic-record-events")
public class SyntheticTopicProducer extends Consumer {

  private final PulseTopicSettings settings;

  public SyntheticTopicProducer(PulseTopicSettings settings) {
    this.settings = settings;
  }

  public Effect onEvent(SyntheticRecordEvent event) {
    // Origin-only mode: a Consumer runs in every region and sees every replicated
    // event. Publish only from the event's origin region so the topic receives each
    // event exactly once across regions.
    if (settings.originOnly() && !messageContext().hasLocalOrigin()) {
      return effects().ignore();
    }

    var recordId = messageContext().eventSubject().orElse("");
    var originRegion = messageContext().originRegion().orElse("");

    var message = switch (event) {
      case RecordCreated created ->
          new SyntheticTopicMessage(recordId, "record-created", created.value(), originRegion);
      case RecordUpdated updated ->
          new SyntheticTopicMessage(recordId, "record-updated", updated.value(), originRegion);
    };

    var metadata = Metadata.EMPTY
        .add("ce-subject", recordId)
        .add("ce-origin-region", originRegion);

    return effects().produce(message, metadata);
  }
}
