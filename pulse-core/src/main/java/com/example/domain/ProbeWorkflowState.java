package com.example.domain;

import java.util.ArrayList;
import java.util.List;

/** State of the multi-region probe workflow. Each field records the region where a handler ran. */
public record ProbeWorkflowState(
    String workflowId,
    String startedIn,
    String stepRanIn,
    List<String> signalsHandledIn,
    String timeoutFiredIn,
    int pauseSeconds,
    String status) {

  public static ProbeWorkflowState start(String workflowId, String region, int pauseSeconds) {
    return new ProbeWorkflowState(workflowId, region, "", List.of(), "", pauseSeconds, "STARTED");
  }

  public ProbeWorkflowState withStep(String region) {
    return new ProbeWorkflowState(
        workflowId, startedIn, region, signalsHandledIn, timeoutFiredIn, pauseSeconds, "PAUSED");
  }

  public ProbeWorkflowState withSignal(String region) {
    var signals = new ArrayList<>(signalsHandledIn);
    signals.add(region);
    return new ProbeWorkflowState(
        workflowId, startedIn, stepRanIn, List.copyOf(signals), timeoutFiredIn, pauseSeconds, status);
  }

  public ProbeWorkflowState withTimeout(String region) {
    return new ProbeWorkflowState(
        workflowId, startedIn, stepRanIn, signalsHandledIn, region, pauseSeconds, "TIMED_OUT");
  }
}
