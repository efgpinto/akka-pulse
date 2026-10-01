package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import com.example.domain.RegionProbeEvent;
import com.example.domain.RegionProbeEvent.ProbeWritten;

import java.time.Duration;

/**
 * Consumer side-effect probe (spec 002, US8, checklist R1 and R4).
 *
 * <p>Records every event in {@code consumer-unguarded-<region>}, and only local-origin events in
 * {@code consumer-guarded-<region>}. It also records each event once more under
 * {@code seen-<region>-<entity>-<seq>}, so a count above 1 shows redelivery and a count of 1 in a
 * region shows the event was processed there. When the write asked for it, it also schedules a
 * timer with the same name in every region, unguarded and guarded.
 */
@Component(id = "region-probe-consumer")
@Consume.FromEventSourcedEntity(RegionProbeEntity.class)
public class RegionProbeConsumer extends Consumer {

  static final Duration TIMER_DELAY = Duration.ofSeconds(5);

  private final ComponentClient componentClient;

  public RegionProbeConsumer(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect onEvent(RegionProbeEvent event) {
    var context = messageContext();
    var region = context.selfRegion();
    var local = context.hasLocalOrigin();
    var command = new ProbeLedgerEntity.RecordCommand(context.originRegion().orElse(""), local, region);

    record("consumer-unguarded-" + region, command);
    if (local) {
      record("consumer-guarded-" + region, command);
    }

    if (event instanceof ProbeWritten written) {
      record("seen-" + region + "-" + context.eventSubject().orElse("") + "-" + written.seq(), command);
    }

    if (event instanceof ProbeWritten written && written.withTimer()) {
      var name = context.eventSubject().orElse("") + "-" + written.seq();
      schedule("probe-u-" + name, "timer-unguarded");
      if (local) {
        schedule("probe-g-" + name, "timer-guarded");
      }
    }
    return effects().done();
  }

  private void record(String ledgerId, ProbeLedgerEntity.RecordCommand command) {
    componentClient.forKeyValueEntity(ledgerId)
        .method(ProbeLedgerEntity::record)
        .invoke(command);
  }

  private void schedule(String timerName, String kind) {
    var call = componentClient.forTimedAction()
        .method(RegionProbeTimedAction::fire)
        .deferred(kind);
    timers().createSingleTimer(timerName, TIMER_DELAY, call);
  }
}
