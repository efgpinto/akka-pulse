package com.example.application;

import akka.Done;
import akka.javasdk.testkit.KeyValueEntityTestKit;
import com.example.domain.HealthCheckEntry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class HealthCheckEntityTest {

  @Test
  public void setCapturesRegionAndGetReadsItBack() {
    var testKit = KeyValueEntityTestKit.of("heartbeat", HealthCheckEntity::new);

    var setResult = testKit.method(HealthCheckEntity::set).invoke();
    assertThat(setResult.isReply()).isTrue();
    assertThat(setResult.getReply()).isEqualTo(Done.getInstance());

    var stored = testKit.getState();
    assertThat(stored.status()).isEqualTo("OK");
    assertThat(stored.timestamp()).isNotNull();
    assertThat(stored.region()).isNotNull();

    var getResult = testKit.method(HealthCheckEntity::get).invoke();
    assertThat(getResult.isReply()).isTrue();
    assertThat(getResult.getReply()).isEqualTo(stored);
  }

  @Test
  public void getBeforeSetReturnsError() {
    var testKit = KeyValueEntityTestKit.of("heartbeat", HealthCheckEntity::new);

    var getResult = testKit.method(HealthCheckEntity::get).invoke();
    assertThat(getResult.isError()).isTrue();
  }
}
