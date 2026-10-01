package com.example.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.api.JwtEndpoint;
import com.example.domain.ProbeLedger;
import com.example.domain.RegionProbeEvent.ProbeWritten;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins current SDK behaviour (spec 002, US8, H2): a message without an origin region reports
 * {@code hasLocalOrigin() == true}, although the {@code OriginAwareContext} Javadoc says topic and
 * service-stream messages always return false. The TestKit sends no origin region, so the
 * "guarded" path runs.
 */
public class RegionProbeConsumerIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withDisabledComponents(Set.of(JwtEndpoint.class))
        .withEventSourcedEntityIncomingMessages(RegionProbeEntity.class);
  }

  @Test
  public void messageWithoutOriginRegionCountsAsLocal() {
    var region = httpClient.GET("/pulse/probe/whoami")
        .responseBodyAs(com.example.api.RegionProbeEndpoint.WhoAmI.class)
        .invoke().body().region();
    var events = testKit.getEventSourcedEntityIncomingMessages(RegionProbeEntity.class);
    events.publish(new ProbeWritten("v", 1, false, "elsewhere", Instant.now()), "p-consumer");

    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> {
          var unguarded = ledger("consumer-unguarded-" + region);
          var guarded = ledger("consumer-guarded-" + region);
          assertThat(unguarded.count()).isEqualTo(1);
          assertThat(unguarded.lastOriginRegion()).isEmpty();
          assertThat(unguarded.lastHasLocalOrigin()).isTrue();
          assertThat(guarded.count()).isEqualTo(1);
        });
  }

  private ProbeLedger ledger(String id) {
    return componentClient.forKeyValueEntity(id).method(ProbeLedgerEntity::get).invoke();
  }
}
