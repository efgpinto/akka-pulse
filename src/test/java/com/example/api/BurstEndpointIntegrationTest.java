package com.example.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class BurstEndpointIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withDisabledComponents(Set.of(JwtEndpoint.class));
  }

  @Test
  public void burstToKeyValueEntitySucceeds() {
    var request = new BurstEndpoint.BurstRequest("kve", 5, 0);
    var response = httpClient.POST("/pulse/burst/")
        .withRequestBody(request)
        .responseBodyAs(BurstEndpoint.BurstResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().target()).isEqualTo("kve");
    assertThat(response.body().requested()).isEqualTo(5);
    assertThat(response.body().succeeded()).isEqualTo(5);
    assertThat(response.body().failed()).isEqualTo(0);
    assertThat(response.body().totalDurationMs()).isGreaterThanOrEqualTo(0);
  }

  @Test
  public void burstToEventSourcedEntitySucceeds() {
    var request = new BurstEndpoint.BurstRequest("ese", 3, 0);
    var response = httpClient.POST("/pulse/burst/")
        .withRequestBody(request)
        .responseBodyAs(BurstEndpoint.BurstResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().succeeded()).isEqualTo(3);
  }

  @Test
  public void burstWithCountOverLimitReturnsError() {
    var request = new BurstEndpoint.BurstRequest("kve", 101, 0);
    var response = httpClient.POST("/pulse/burst/")
        .withRequestBody(request)
        .invoke();

    assertThat(response.status().isSuccess()).isFalse();
  }

  @Test
  public void burstWithInvalidTargetReturnsError() {
    var request = new BurstEndpoint.BurstRequest("invalid-target", 5, 0);
    var response = httpClient.POST("/pulse/burst/")
        .withRequestBody(request)
        .invoke();

    assertThat(response.status().isSuccess()).isFalse();
  }
}
