package com.example.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TopicMessageCounterEntityTest {

  @Test
  public void incrementCountsMessagesAndTracksOriginRegion() {
    var testKit = KeyValueEntityTestKit.of("synthetic-record-events", TopicMessageCounterEntity::new);

    var r1 = testKit.method(TopicMessageCounterEntity::increment)
        .invoke(new TopicMessageCounterEntity.IncrementCommand("gcp-us-east1"));
    assertThat(r1.isReply()).isTrue();
    assertThat(r1.getReply().messageCount()).isEqualTo(1);
    assertThat(r1.getReply().lastOriginRegion()).isEqualTo("gcp-us-east1");

    testKit.method(TopicMessageCounterEntity::increment)
        .invoke(new TopicMessageCounterEntity.IncrementCommand("gcp-europe-west1"));
    var state = testKit.getState();
    assertThat(state.messageCount()).isEqualTo(2);
    assertThat(state.lastOriginRegion()).isEqualTo("gcp-europe-west1");
  }

  @Test
  public void getBeforeAnyMessageReturnsEmpty() {
    var testKit = KeyValueEntityTestKit.of("synthetic-record-events", TopicMessageCounterEntity::new);

    var result = testKit.method(TopicMessageCounterEntity::get).invoke();
    assertThat(result.isReply()).isTrue();
    assertThat(result.getReply().messageCount()).isEqualTo(0);
  }
}
