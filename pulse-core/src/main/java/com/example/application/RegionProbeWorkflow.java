package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.workflow.WorkflowContext;
import com.example.domain.ProbeWorkflowState;

import static java.time.Duration.ofSeconds;

/**
 * Workflow probe (spec 002, US8). Records the region of the start command, the step, each signal
 * and the pause timeout. The pause keeps the workflow alive so signals can be sent from other
 * regions, and its timeout is a region-local timer (checklist R4, R15).
 *
 * <p>The step stands in for a call to a third-party service: each execution increments the ledger
 * {@code wf-step-<workflowId>-<region>} in the region that ran it, so a count above 1, or counts in
 * two regions, show that the step ran more than once (for example after a region failover).
 */
@Component(id = "region-probe-workflow")
public class RegionProbeWorkflow extends Workflow<ProbeWorkflowState> {

  public record StartCommand(int pauseSeconds, int stepDelaySeconds) {}
  /** {@code callMicros} is set by the endpoint, as in {@link com.example.domain.ProbeReading}. */
  public record ProbeWorkflowReply(ProbeWorkflowState state, String handledIn, Long callMicros) {
    public ProbeWorkflowReply(ProbeWorkflowState state, String handledIn) {
      this(state, handledIn, null);
    }

    public ProbeWorkflowReply withCallMicros(long micros) {
      return new ProbeWorkflowReply(state, handledIn, micros);
    }
  }

  private final String region;
  private final ComponentClient componentClient;

  public RegionProbeWorkflow(WorkflowContext context, ComponentClient componentClient) {
    this.region = context.selfRegion();
    this.componentClient = componentClient;
  }

  @Override
  public WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .stepTimeout(RegionProbeWorkflow::recordStep, ofSeconds(MAX_STEP_DELAY_SECONDS + 30))
        .build();
  }

  static final int MAX_STEP_DELAY_SECONDS = 300;

  @Override
  public ProbeWorkflowState emptyState() {
    return null;
  }

  public Effect<ProbeWorkflowReply> start(StartCommand command) {
    if (currentState() != null) {
      return effects().error("Workflow already started");
    }
    if (command.pauseSeconds() < 1 || command.pauseSeconds() > 86400) {
      return effects().error("pauseSeconds must be between 1 and 86400");
    }
    if (command.stepDelaySeconds() < 0 || command.stepDelaySeconds() > MAX_STEP_DELAY_SECONDS) {
      return effects().error("stepDelaySeconds must be between 0 and " + MAX_STEP_DELAY_SECONDS);
    }
    var state = ProbeWorkflowState.start(
        commandContext().workflowId(), commandContext().selfRegion(), command.pauseSeconds(),
        command.stepDelaySeconds());
    return effects()
        .updateState(state)
        .transitionTo(RegionProbeWorkflow::recordStep)
        .thenReply(new ProbeWorkflowReply(state, commandContext().selfRegion()));
  }

  /** A write command: the runtime must run it on the workflow's primary. */
  public Effect<ProbeWorkflowReply> signal() {
    if (currentState() == null) {
      return effects().error("Workflow not found");
    }
    if (!"PAUSED".equals(currentState().status())) {
      return effects().error("Workflow is not paused");
    }
    var handledIn = commandContext().selfRegion();
    var state = currentState().withSignal(handledIn);
    return effects()
        .updateState(state)
        .pause(pauseSettings(state.pauseSeconds()))
        .thenReply(new ProbeWorkflowReply(state, handledIn));
  }

  public ReadOnlyEffect<ProbeWorkflowReply> status() {
    if (currentState() == null) {
      return effects().error("Workflow not found");
    }
    return effects().reply(new ProbeWorkflowReply(currentState(), commandContext().selfRegion()));
  }

  public Effect<Done> onPauseTimeout() {
    return effects()
        .updateState(currentState().withTimeout(region))
        .end()
        .thenReply(Done.getInstance());
  }

  @StepName("record")
  private StepEffect recordStep() {
    var state = currentState();
    componentClient.forKeyValueEntity("wf-step-" + state.workflowId() + "-" + region)
        .method(ProbeLedgerEntity::record)
        .invoke(new ProbeLedgerEntity.RecordCommand("", false, region));
    if (state.stepDelaySeconds() > 0) {
      try {
        Thread.sleep(state.stepDelaySeconds() * 1000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return stepEffects()
        .updateState(currentState().withStep(region))
        .thenPause(pauseSettings(currentState().pauseSeconds()));
  }

  private PauseSettings pauseSettings(int seconds) {
    return pauseSetting(ofSeconds(seconds)).timeoutHandler(RegionProbeWorkflow::onPauseTimeout);
  }
}
