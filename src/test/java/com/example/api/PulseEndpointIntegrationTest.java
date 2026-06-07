package com.example.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.api.JwtEndpoint;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class PulseEndpointIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withDisabledComponents(Set.of(JwtEndpoint.class));
  }

  @Test
  public void healthEndpointReturnsUpStatus() {
    var response = httpClient.GET("/pulse/health")
        .responseBodyAs(PulseEndpoint.HealthUpResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().status()).isEqualTo("UP");
    assertThat(response.body().serviceName()).isEqualTo("akka-pulse");
    assertThat(response.body().version()).isEqualTo("1.0-SNAPSHOT");
    assertThat(response.body().timestamp()).isNotNull();
    assertThat(response.body().persistenceCheck().status()).isEqualTo("OK");
    assertThat(response.body().persistenceCheck().latencyMs()).isGreaterThanOrEqualTo(0);
  }
}
