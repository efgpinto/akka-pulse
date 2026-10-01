package com.example.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.application.RegionProbeView;
import com.example.application.RegionProbeWorkflow;
import com.example.domain.ProbeLedger;
import com.example.domain.ProbeReading;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Single-region smoke test of the multi-region probes (spec 002, US8). */
public class RegionProbeEndpointIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withDisabledComponents(Set.of(JwtEndpoint.class));
  }

  private String region() {
    return httpClient.GET("/pulse/probe/whoami")
        .responseBodyAs(RegionProbeEndpoint.WhoAmI.class)
        .invoke().body().region();
  }

  private ProbeReading post(String path, boolean withTimer) {
    return httpClient.POST(path)
        .withRequestBody(new RegionProbeEndpoint.WriteRequest("v", withTimer))
        .responseBodyAs(ProbeReading.class)
        .invoke().body();
  }

  private <T> T get(String path, Class<T> type) {
    return httpClient.GET(path).responseBodyAs(type).invoke().body();
  }

  @Test
  public void entityWritesAndReadsReportTheLocalRegion() {
    var region = region();
    for (var kind : new String[] {"ese", "kve", "filtered-ese", "filtered-kve"}) {
      var written = post("/pulse/probe/" + kind + "/it-" + kind, false);
      assertThat(written.handledIn()).isEqualTo(region);
      assertThat(written.state().writtenIn()).isEqualTo(region);

      var plain = get("/pulse/probe/" + kind + "/it-" + kind + "/plain", ProbeReading.class);
      var readOnly = get("/pulse/probe/" + kind + "/it-" + kind + "/read-only", ProbeReading.class);
      assertThat(plain.state().seq()).isEqualTo(1);
      assertThat(readOnly.state().seq()).isEqualTo(1);
    }
  }

  @Test
  public void consumerViewAndTimersRecordTheLocalRegion() {
    var region = region();
    post("/pulse/probe/ese/it-side-effects", true);

    Awaitility.await()
        .atMost(20, TimeUnit.SECONDS)
        .untilAsserted(() -> {
          var rows = get("/pulse/probe/view/it-side-effects", RegionProbeView.ProbeRows.class).entries();
          assertThat(rows).hasSize(1);
          assertThat(rows.getFirst().builtIn()).isEqualTo(region);

          assertThat(get("/pulse/probe/ledger/consumer-unguarded-" + region, ProbeLedger.class).count())
              .isGreaterThanOrEqualTo(1);
          assertThat(get("/pulse/probe/ledger/timer-unguarded-" + region, ProbeLedger.class).count())
              .isGreaterThanOrEqualTo(1);
          assertThat(get("/pulse/probe/ledger/timer-guarded-" + region, ProbeLedger.class).count())
              .isGreaterThanOrEqualTo(1);
        });
  }

  @Test
  public void workflowRecordsRegionOfStartStepSignalAndTimeout() {
    var region = region();
    httpClient.POST("/pulse/probe/workflow/it-wf/start")
        .withRequestBody(new RegionProbeWorkflow.StartCommand(3))
        .invoke();

    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(status().state().status()).isEqualTo("PAUSED"));

    var signal = httpClient.POST("/pulse/probe/workflow/it-wf/signal")
        .responseBodyAs(RegionProbeWorkflow.ProbeWorkflowReply.class)
        .invoke().body();
    assertThat(signal.handledIn()).isEqualTo(region);

    Awaitility.await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(() -> {
          var state = status().state();
          assertThat(state.status()).isEqualTo("TIMED_OUT");
          assertThat(state.startedIn()).isEqualTo(region);
          assertThat(state.stepRanIn()).isEqualTo(region);
          assertThat(state.signalsHandledIn()).containsExactly(region);
          assertThat(state.timeoutFiredIn()).isEqualTo(region);
        });
  }

  private RegionProbeWorkflow.ProbeWorkflowReply status() {
    return get("/pulse/probe/workflow/it-wf", RegionProbeWorkflow.ProbeWorkflowReply.class);
  }
}
