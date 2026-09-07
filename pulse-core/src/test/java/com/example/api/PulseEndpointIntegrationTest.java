package com.example.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.api.JwtEndpoint;
import com.example.common.api.HealthEndpoint;
import com.example.domain.TopicMessageCounter;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class PulseEndpointIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withDisabledComponents(Set.of(JwtEndpoint.class));
  }

  // The health endpoint is provided by the pulse-common library jar; this test is the
  // discovery gate proving library-packaged components are served by this service.
  @Test
  public void healthEndpointReturnsUpStatus() {
    var response = httpClient.GET("/pulse/health")
        .responseBodyAs(HealthEndpoint.HealthUpResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().status()).isEqualTo("UP");
    assertThat(response.body().serviceName()).isEqualTo("pulse-core");
    assertThat(response.body().version()).isEqualTo("1.0-SNAPSHOT");
    assertThat(response.body().region()).isNotNull();
    assertThat(response.body().timestamp()).isNotNull();
    assertThat(response.body().persistenceCheck().status()).isEqualTo("OK");
    assertThat(response.body().persistenceCheck().latencyMs()).isGreaterThanOrEqualTo(0);
  }

  // The testkit enforces the endpoint ACL: an unidentified caller is denied, a caller
  // impersonating the pulse-peer service (dev-mode impersonate-service header) is allowed.
  @Test
  public void internalPingDeniesCallersOtherThanPulsePeer() {
    try {
      httpClient.GET("/pulse/internal/ping")
          .responseBodyAs(InternalPingEndpoint.PingResponse.class)
          .invoke();
      throw new AssertionError("Expected the request without a service identity to be denied");
    } catch (RuntimeException e) {
      assertThat(e.getMessage()).contains("403");
    }
  }

  @Test
  public void internalPingRespondsForPulsePeer() {
    var response = httpClient.GET("/pulse/internal/ping")
        .addHeader("impersonate-service", "pulse-peer")
        .responseBodyAs(InternalPingEndpoint.PingResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().serviceName()).isEqualTo("pulse-core");
    assertThat(response.body().region()).isNotNull();
  }

  @Test
  public void topicCounterEndpointReturnsEmptyForUnseenCounter() {
    var response = httpClient.GET("/pulse/topic-counter/synthetic-record-events")
        .responseBodyAs(TopicMessageCounter.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().messageCount()).isEqualTo(0);
  }
}
