package com.example.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * State of the multi-region probe workflow. Each field records the region where a handler ran.
 *
 * <p>{@code stepDelaySeconds} keeps the step running for that long, so a region failover can be
 * triggered while the step is in flight.
 */
public record ProbeWorkflowState(
    String workflowId,
    String startedIn,
    String stepRanIn,
    List<String> signalsHandledIn,
    String timeoutFiredIn,
    int pauseSeconds,
    int stepDelaySeconds,
    String status) {

  public static ProbeWorkflowState start(
      String workflowId, String region, int pauseSeconds, int stepDelaySeconds) {
    return new ProbeWorkflowState(
        workflowId, region, "", List.of(), "", pauseSeconds, stepDelaySeconds, "STARTED");
  }

  public ProbeWorkflowState withStep(String region) {
    return new ProbeWorkflowState(
        workflowId, startedIn, region, signalsHandledIn, timeoutFiredIn, pauseSeconds,
        stepDelaySeconds, "PAUSED");
  }

  public ProbeWorkflowState withSignal(String region) {
    var signals = new ArrayList<>(signalsHandledIn);
    signals.add(region);
    return new ProbeWorkflowState(
        workflowId, startedIn, stepRanIn, List.copyOf(signals), timeoutFiredIn, pauseSeconds,
        stepDelaySeconds, status);
  }

  public ProbeWorkflowState withTimeout(String region) {
    return new ProbeWorkflowState(
        workflowId, startedIn, stepRanIn, signalsHandledIn, region, pauseSeconds, stepDelaySeconds,
        "TIMED_OUT");
  }
}
