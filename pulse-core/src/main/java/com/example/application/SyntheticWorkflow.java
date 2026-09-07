package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.workflow.Workflow;
import com.example.domain.SyntheticWorkflowState;

import static java.time.Duration.ofSeconds;

@Component(id = "synthetic-workflow")
public class SyntheticWorkflow extends Workflow<SyntheticWorkflowState> {

  public record StartWorkflowRequest(String input, String mode, int delaySeconds) {}

  @Override
  public SyntheticWorkflowState emptyState() {
    return null;
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .defaultStepTimeout(ofSeconds(60))
        .stepTimeout(SyntheticWorkflow::persistStep, ofSeconds(310))
        .stepRecovery(
            SyntheticWorkflow::persistStep,
            RecoverStrategy.maxRetries(1).failoverTo(SyntheticWorkflow::compensateStep))
        .build();
  }

  public Effect<Done> start(StartWorkflowRequest request) {
    if (currentState() != null) {
      return effects().error("Workflow already started");
    }
    if (request.input() == null || request.input().isBlank()) {
      return effects().error("Input must not be empty");
    }
    if (request.delaySeconds() < 0 || request.delaySeconds() > 300) {
      return effects().error("delaySeconds must be between 0 and 300");
    }
    var state = SyntheticWorkflowState.start(
        commandContext().workflowId(), request.input(), request.delaySeconds());
    return effects()
        .updateState(state)
        .transitionTo(SyntheticWorkflow::validateStep)
        .thenReply(Done.getInstance());
  }

  public Effect<SyntheticWorkflowState> getStatus() {
    if (currentState() == null) {
      return effects().error("Workflow not found");
    }
    return effects().reply(currentState());
  }

  @StepName("validate")
  private StepEffect validateStep() {
    var state = currentState();
    return stepEffects()
        .updateState(state.withStatus("VALIDATED"))
        .thenTransitionTo(SyntheticWorkflow::persistStep);
  }

  @StepName("persist")
  private StepEffect persistStep() {
    var state = currentState();

    if ("trigger-failure".equals(state.input())) {
      throw new RuntimeException("Deliberate failure for testing compensation");
    }

    if (state.delaySeconds() > 0) {
      try {
        Thread.sleep(state.delaySeconds() * 1000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    componentClient.forKeyValueEntity("wf-" + state.workflowId())
        .method(SyntheticEntryEntity::set)
        .invoke(new SyntheticEntryEntity.SetCommand("workflow-result:" + state.input(), 0));

    return stepEffects()
        .updateState(state.withStatus("COMPLETED"))
        .thenEnd();
  }

  @StepName("compensate")
  private StepEffect compensateStep() {
    var state = currentState();
    return stepEffects()
        .updateState(state.withFailure("Persist step failed — compensated"))
        .thenEnd();
  }

  private final akka.javasdk.client.ComponentClient componentClient;

  public SyntheticWorkflow(akka.javasdk.client.ComponentClient componentClient) {
    this.componentClient = componentClient;
  }
}
