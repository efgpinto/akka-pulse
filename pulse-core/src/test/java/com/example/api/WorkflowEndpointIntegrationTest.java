package com.example.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.example.application.SyntheticWorkflow;
import com.example.domain.SyntheticWorkflowState;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class WorkflowEndpointIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withDisabledComponents(Set.of(JwtEndpoint.class));
  }

  @Test
  public void normalModeCompletesWorkflow() {
    var request = new SyntheticWorkflow.StartWorkflowRequest("test-data", "normal", 0);
    var startResponse = httpClient.POST("/pulse/workflows/wf-normal/start")
        .withRequestBody(request)
        .invoke();
    assertThat(startResponse.status().isSuccess()).isTrue();

    Awaitility.await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(() -> {
          var status = httpClient.GET("/pulse/workflows/wf-normal")
              .responseBodyAs(SyntheticWorkflowState.class)
              .invoke();
          assertThat(status.body().status()).isEqualTo("COMPLETED");
        });
  }

  @Test
  public void triggerFailureModeCompensatesWorkflow() {
    var request = new SyntheticWorkflow.StartWorkflowRequest("trigger-failure", "trigger-failure", 0);
    var startResponse = httpClient.POST("/pulse/workflows/wf-fail/start")
        .withRequestBody(request)
        .invoke();
    assertThat(startResponse.status().isSuccess()).isTrue();

    Awaitility.await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(() -> {
          var status = httpClient.GET("/pulse/workflows/wf-fail")
              .responseBodyAs(SyntheticWorkflowState.class)
              .invoke();
          assertThat(status.body().status()).isEqualTo("COMPENSATED");
        });
  }

  @Test
  public void getStatusReturnsCurrentState() {
    var request = new SyntheticWorkflow.StartWorkflowRequest("status-test", "normal", 0);
    httpClient.POST("/pulse/workflows/wf-status/start")
        .withRequestBody(request)
        .invoke();

    Awaitility.await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(() -> {
          var status = httpClient.GET("/pulse/workflows/wf-status")
              .responseBodyAs(SyntheticWorkflowState.class)
              .invoke();
          assertThat(status.body()).isNotNull();
          assertThat(status.body().input()).isEqualTo("status-test");
        });
  }
}
