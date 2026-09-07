package com.example.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.common.domain.PulseStreamEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public class SyntheticRecordStreamProducerIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withStreamOutgoingMessages("pulse-core", "synthetic-records");
  }

  @Test
  public void transformsInternalEventsToPublicStreamEvents() {
    var outgoing = testKit.getStreamOutgoingMessages("pulse-core", "synthetic-records");

    componentClient.forEventSourcedEntity("stream-rec-1")
        .method(SyntheticRecordEntity::create)
        .invoke(new SyntheticRecordEntity.CreateCommand("stream-name", "v1", 0));

    var created = outgoing.expectOneTyped(PulseStreamEvent.RecordCreated.class, Duration.ofSeconds(20));
    assertThat(created.getPayload().name()).isEqualTo("stream-name");
    assertThat(created.getPayload().value()).isEqualTo("v1");

    componentClient.forEventSourcedEntity("stream-rec-1")
        .method(SyntheticRecordEntity::update)
        .invoke(new SyntheticRecordEntity.UpdateCommand("v2", 0));

    var updated = outgoing.expectOneTyped(PulseStreamEvent.RecordUpdated.class, Duration.ofSeconds(20));
    assertThat(updated.getPayload().value()).isEqualTo("v2");
    assertThat(updated.getPayload().version()).isEqualTo(2);
  }
}
