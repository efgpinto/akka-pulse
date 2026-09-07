package com.example.peer.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.common.api.HealthEndpoint;
import com.example.common.domain.PulseStreamEvent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class PeerProbeEndpointIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withStreamIncomingMessages("pulse-core", "synthetic-records");
  }

  // Library reuse (SC-002): the health endpoint comes from the pulse-common jar and identifies
  // this service as pulse-peer.
  @Test
  public void healthEndpointIsServedFromLibraryWithPeerIdentity() {
    var response = httpClient.GET("/pulse/health")
        .responseBodyAs(HealthEndpoint.HealthUpResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().status()).isEqualTo("UP");
    assertThat(response.body().serviceName()).isEqualTo("pulse-peer");
  }

  // Single ordered test: the counter is shared runtime state, so the zero case must be
  // asserted before any event is published.
  @Test
  public void streamProbeCountsEventsConsumedFromCoreStream() {
    var before = httpClient.GET("/peer/probes/stream")
        .responseBodyAs(PeerProbeEndpoint.StreamProbeResult.class)
        .invoke();
    assertThat(before.status().isSuccess()).isTrue();
    assertThat(before.body().passed()).isFalse();
    assertThat(before.body().consumedCount()).isEqualTo(0);

    var upstream = testKit.getStreamIncomingMessages("pulse-core", "synthetic-records");
    upstream.publish(new PulseStreamEvent.RecordCreated("n1", "v1", Instant.now()), "rec-1");
    upstream.publish(new PulseStreamEvent.RecordUpdated("v2", 2, Instant.now()), "rec-1");

    Awaitility.await()
        .atMost(20, TimeUnit.SECONDS)
        .untilAsserted(() -> {
          var response = httpClient.GET("/peer/probes/stream")
              .responseBodyAs(PeerProbeEndpoint.StreamProbeResult.class)
              .invoke();
          assertThat(response.status().isSuccess()).isTrue();
          assertThat(response.body().passed()).isTrue();
          assertThat(response.body().consumedCount()).isEqualTo(2);
          assertThat(response.body().lastEventAt()).isNotNull();
        });
  }

  // pulse-core is not running in this testkit, so the s2s probes must report a structured
  // failure (edge case: peer deployed before core), never a 5xx.
  @Test
  public void directProbeFailsCleanlyWhenCoreUnreachable() {
    var response = httpClient.GET("/peer/probes/direct")
        .responseBodyAs(PeerProbeEndpoint.DirectProbeResult.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().passed()).isFalse();
    assertThat(response.body().target()).isEqualTo("pulse-core");
    assertThat(response.body().evidence()).isNotBlank();
  }

  @Test
  public void restrictedProbeFailsCleanlyWhenCoreUnreachable() {
    var response = httpClient.GET("/peer/probes/restricted")
        .responseBodyAs(PeerProbeEndpoint.RestrictedProbeResult.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().passed()).isFalse();
    assertThat(response.body().target()).isEqualTo("pulse-core");
    assertThat(response.body().evidence()).isNotBlank();
  }
}
