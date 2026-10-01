package com.example.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.workflow.WorkflowContext;
import com.example.domain.ProbeWorkflowState;

import static java.time.Duration.ofSeconds;

/**
 * Workflow probe (spec 002, US8). Records the region of the start command, the step, each signal
 * and the pause timeout. The pause keeps the workflow alive so signals can be sent from other
 * regions, and its timeout is a region-local timer (checklist R4, R15).
 */
@Component(id = "region-probe-workflow")
public class RegionProbeWorkflow extends Workflow<ProbeWorkflowState> {

  public record StartCommand(int pauseSeconds) {}
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

  public RegionProbeWorkflow(WorkflowContext context) {
    this.region = context.selfRegion();
  }

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
    var state = ProbeWorkflowState.start(
        commandContext().workflowId(), commandContext().selfRegion(), command.pauseSeconds());
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
    return stepEffects()
        .updateState(currentState().withStep(region))
        .thenPause(pauseSettings(currentState().pauseSeconds()));
  }

  private PauseSettings pauseSettings(int seconds) {
    return pauseSetting(ofSeconds(seconds)).timeoutHandler(RegionProbeWorkflow::onPauseTimeout);
  }
}
