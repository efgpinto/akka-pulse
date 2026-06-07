package com.example.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SyntheticEntryEntityTest {

  @Test
  public void setStoresValue() {
    var testKit = KeyValueEntityTestKit.of("entry-1", SyntheticEntryEntity::new);
    var result = testKit.method(SyntheticEntryEntity::set)
        .invoke(new SyntheticEntryEntity.SetCommand("my-data", 0));

    assertThat(result.isReply()).isTrue();
    var state = testKit.getState();
    assertThat(state.entryId()).isEqualTo("entry-1");
    assertThat(state.data()).isEqualTo("my-data");
    assertThat(state.lastUpdated()).isNotNull();
  }

  @Test
  public void updateOverwritesValue() {
    var testKit = KeyValueEntityTestKit.of("entry-1", SyntheticEntryEntity::new);
    testKit.method(SyntheticEntryEntity::set)
        .invoke(new SyntheticEntryEntity.SetCommand("initial", 0));

    testKit.method(SyntheticEntryEntity::set)
        .invoke(new SyntheticEntryEntity.SetCommand("updated", 0));

    var state = testKit.getState();
    assertThat(state.data()).isEqualTo("updated");
  }

  @Test
  public void deleteClearsState() {
    var testKit = KeyValueEntityTestKit.of("entry-1", SyntheticEntryEntity::new);
    testKit.method(SyntheticEntryEntity::set)
        .invoke(new SyntheticEntryEntity.SetCommand("data", 0));

    var result = testKit.method(SyntheticEntryEntity::delete).invoke();
    assertThat(result.isReply()).isTrue();
  }

  @Test
  public void setWithInvalidDelayReturnsError() {
    var testKit = KeyValueEntityTestKit.of("entry-1", SyntheticEntryEntity::new);
    var result = testKit.method(SyntheticEntryEntity::set)
        .invoke(new SyntheticEntryEntity.SetCommand("data", 301));

    assertThat(result.isError()).isTrue();
  }

  @Test
  public void getOnNonExistentReturnsError() {
    var testKit = KeyValueEntityTestKit.of("entry-1", SyntheticEntryEntity::new);
    var result = testKit.method(SyntheticEntryEntity::get).invoke();

    assertThat(result.isError()).isTrue();
  }
}
