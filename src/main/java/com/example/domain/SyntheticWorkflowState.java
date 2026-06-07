package com.example.domain;

public record SyntheticWorkflowState(
    String workflowId,
    String input,
    String status,
    String failureReason,
    int delaySeconds) {

  public static SyntheticWorkflowState start(String workflowId, String input, int delaySeconds) {
    return new SyntheticWorkflowState(workflowId, input, "STARTED", "", delaySeconds);
  }

  public SyntheticWorkflowState withStatus(String newStatus) {
    return new SyntheticWorkflowState(workflowId, input, newStatus, failureReason, delaySeconds);
  }

  public SyntheticWorkflowState withFailure(String reason) {
    return new SyntheticWorkflowState(workflowId, input, "COMPENSATED", reason, delaySeconds);
  }
}
