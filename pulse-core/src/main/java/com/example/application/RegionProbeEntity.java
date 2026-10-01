package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import com.example.domain.ProbeReading;
import com.example.domain.RegionProbeEvent;
import com.example.domain.RegionProbeEvent.ProbeWritten;
import com.example.domain.RegionProbeState;

import java.time.Instant;

/**
 * Multi-region probe (spec 002, US8). Every reply names the region where the command handler ran.
 *
 * <p>{@code readPlain} and {@code readOnly} return the same data. They differ only in the declared
 * effect type, which decides whether the runtime treats the call as a write.
 */
@Component(id = "region-probe")
public class RegionProbeEntity extends EventSourcedEntity<RegionProbeState, RegionProbeEvent> {

  private final String entityId;

  public RegionProbeEntity(EventSourcedEntityContext context) {
    this.entityId = context.entityId();
  }

  public record WriteCommand(String value, boolean withTimer) {}

  @Override
  public RegionProbeState emptyState() {
    return null;
  }

  public Effect<ProbeReading> write(WriteCommand command) {
    var region = commandContext().selfRegion();
    var seq = currentState() == null ? 1 : currentState().seq() + 1;
    var event = new ProbeWritten(command.value(), seq, command.withTimer(), region, Instant.now());
    return effects().persist(event).thenReply(state -> new ProbeReading(state, region));
  }

  public Effect<ProbeReading> readPlain() {
    return effects().reply(new ProbeReading(currentState(), commandContext().selfRegion()));
  }

  public ReadOnlyEffect<ProbeReading> readOnly() {
    return effects().reply(new ProbeReading(currentState(), commandContext().selfRegion()));
  }

  @Override
  public RegionProbeState applyEvent(RegionProbeEvent event) {
    return switch (event) {
      // appliedIn and appliedAt are region-local on purpose: they show that applyEvent runs
      // independently in each region (checklist R2). Never do this in a real entity.
      case ProbeWritten written -> new RegionProbeState(
          entityId, written.value(), written.seq(), written.writtenIn(), written.writtenAt(),
          eventContext().selfRegion(), Instant.now());
    };
  }
}
