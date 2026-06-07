package com.example.application;

import akka.javasdk.testkit.EventSourcedTestKit;
import com.example.domain.SyntheticRecord;
import com.example.domain.SyntheticRecordEvent.RecordCreated;
import com.example.domain.SyntheticRecordEvent.RecordUpdated;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SyntheticRecordEntityTest {

  @Test
  public void createPersistsRecordCreatedEvent() {
    var testKit = EventSourcedTestKit.of("record-1", SyntheticRecordEntity::new);
    var command = new SyntheticRecordEntity.CreateCommand("test-name", "test-value", 0);
    var result = testKit.method(SyntheticRecordEntity::create).invoke(command);

    assertThat(result.isReply()).isTrue();
    var event = result.getAllEvents().getFirst();
    assertThat(event).isInstanceOf(RecordCreated.class);
    var created = (RecordCreated) event;
    assertThat(created.name()).isEqualTo("test-name");
    assertThat(created.value()).isEqualTo("test-value");

    var state = testKit.getState();
    assertThat(state.recordId()).isEqualTo("record-1");
    assertThat(state.name()).isEqualTo("test-name");
    assertThat(state.value()).isEqualTo("test-value");
    assertThat(state.status()).isEqualTo("CREATED");
    assertThat(state.version()).isEqualTo(1);
  }

  @Test
  public void updatePersistsRecordUpdatedEvent() {
    var testKit = EventSourcedTestKit.of("record-1", SyntheticRecordEntity::new);
    testKit.method(SyntheticRecordEntity::create)
        .invoke(new SyntheticRecordEntity.CreateCommand("test-name", "initial", 0));

    var result = testKit.method(SyntheticRecordEntity::update)
        .invoke(new SyntheticRecordEntity.UpdateCommand("updated-value", 0));

    assertThat(result.isReply()).isTrue();
    var event = result.getAllEvents().getFirst();
    assertThat(event).isInstanceOf(RecordUpdated.class);
    var updated = (RecordUpdated) event;
    assertThat(updated.value()).isEqualTo("updated-value");
    assertThat(updated.version()).isEqualTo(2);

    var state = testKit.getState();
    assertThat(state.value()).isEqualTo("updated-value");
    assertThat(state.status()).isEqualTo("UPDATED");
    assertThat(state.version()).isEqualTo(2);
  }

  @Test
  public void getOnNonExistentReturnsError() {
    var testKit = EventSourcedTestKit.of("record-1", SyntheticRecordEntity::new);
    var result = testKit.method(SyntheticRecordEntity::get).invoke();

    assertThat(result.isError()).isTrue();
  }

  @Test
  public void createWithInvalidDelayReturnsError() {
    var testKit = EventSourcedTestKit.of("record-1", SyntheticRecordEntity::new);
    var command = new SyntheticRecordEntity.CreateCommand("test", "value", 301);
    var result = testKit.method(SyntheticRecordEntity::create).invoke(command);

    assertThat(result.isError()).isTrue();
  }

  @Test
  public void createDuplicateReturnsError() {
    var testKit = EventSourcedTestKit.of("record-1", SyntheticRecordEntity::new);
    testKit.method(SyntheticRecordEntity::create)
        .invoke(new SyntheticRecordEntity.CreateCommand("test", "value", 0));

    var result = testKit.method(SyntheticRecordEntity::create)
        .invoke(new SyntheticRecordEntity.CreateCommand("test2", "value2", 0));

    assertThat(result.isError()).isTrue();
  }
}
