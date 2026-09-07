package com.example.application;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Produce;
import akka.javasdk.consumer.Consumer;
import com.example.common.domain.PulseStreamEvent;
import com.example.domain.SyntheticRecordEvent;
import com.example.domain.SyntheticRecordEvent.RecordCreated;
import com.example.domain.SyntheticRecordEvent.RecordUpdated;

/**
 * Publishes synthetic record events as the cross-service stream "synthetic-records" so a sibling
 * service (pulse-peer) can validate service-to-service eventing without a message broker.
 */
@Component(id = "synthetic-record-stream-producer")
@Consume.FromEventSourcedEntity(SyntheticRecordEntity.class)
@Produce.ServiceStream(id = "synthetic-records")
@Acl(allow = @Acl.Matcher(service = "*"))
public class SyntheticRecordStreamProducer extends Consumer {

  public Effect onEvent(SyntheticRecordEvent event) {
    return switch (event) {
      case RecordCreated created -> effects()
          .produce(new PulseStreamEvent.RecordCreated(created.name(), created.value(), created.createdAt()));
      case RecordUpdated updated -> effects()
          .produce(new PulseStreamEvent.RecordUpdated(updated.value(), updated.version(), updated.updatedAt()));
    };
  }
}
