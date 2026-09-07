package com.example.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.domain.SyntheticTopicMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public class SyntheticTopicProducerIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("pulse.topic.enabled = true")
        .withTopicOutgoingMessages("synthetic-record-events");
  }

  @Test
  public void publishesOneMessageForLocalOriginCreate() {
    var eventsTopic = testKit.getTopicOutgoingMessages("synthetic-record-events");

    componentClient.forEventSourcedEntity("rec-1")
        .method(SyntheticRecordEntity::create)
        .invoke(new SyntheticRecordEntity.CreateCommand("n1", "v1", 0));

    var msg = eventsTopic.expectOneTyped(SyntheticTopicMessage.class, Duration.ofSeconds(20));
    assertThat(msg.getPayload().recordId()).isEqualTo("rec-1");
    assertThat(msg.getPayload().eventType()).isEqualTo("record-created");
    assertThat(msg.getPayload().value()).isEqualTo("v1");
  }
}
