package com.example.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.domain.SyntheticRecordEvent.RecordCreated;
import com.example.domain.SyntheticRecordEvent.RecordUpdated;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.awaitility.Awaitility;

public class SyntheticRecordViewIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withEventSourcedEntityIncomingMessages(SyntheticRecordEntity.class);
  }

  @Test
  public void viewProjectsCreatedEvents() {
    var events = testKit.getEventSourcedEntityIncomingMessages(SyntheticRecordEntity.class);
    events.publish(new RecordCreated("test-name", "test-value", Instant.now()), "record-1");

    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> {
          var result = componentClient.forView()
              .method(SyntheticRecordView::getByName)
              .invoke("test-name");
          assertThat(result.entries()).isNotEmpty();
          assertThat(result.entries().getFirst().name()).isEqualTo("test-name");
          assertThat(result.entries().getFirst().value()).isEqualTo("test-value");
          assertThat(result.entries().getFirst().status()).isEqualTo("CREATED");
        });
  }

  @Test
  public void viewReflectsUpdatedEvents() {
    var events = testKit.getEventSourcedEntityIncomingMessages(SyntheticRecordEntity.class);
    events.publish(new RecordCreated("update-test", "initial", Instant.now()), "record-2");
    events.publish(new RecordUpdated("updated-value", 2, Instant.now()), "record-2");

    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> {
          var result = componentClient.forView()
              .method(SyntheticRecordView::getByName)
              .invoke("update-test");
          assertThat(result.entries()).isNotEmpty();
          assertThat(result.entries().getFirst().value()).isEqualTo("updated-value");
          assertThat(result.entries().getFirst().status()).isEqualTo("UPDATED");
        });
  }
}
