package com.example.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.domain.SyntheticTopicMessage;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class SyntheticTopicConsumerIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig("pulse.topic.enabled = true")
        .withTopicIncomingMessages("synthetic-record-events");
  }

  @Test
  public void countsMessagesReadFromTopic() {
    var topic = testKit.getTopicIncomingMessages("synthetic-record-events");

    topic.publish(new SyntheticTopicMessage("rec-1", "record-created", "v1", "gcp-us-east1"), "rec-1");

    Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
      var counter = componentClient.forKeyValueEntity(SyntheticTopicConsumer.COUNTER_ID)
          .method(TopicMessageCounterEntity::get)
          .invoke();
      assertThat(counter.messageCount()).isEqualTo(1);
      assertThat(counter.lastOriginRegion()).isEqualTo("gcp-us-east1");
    });
  }
}
