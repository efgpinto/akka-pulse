package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.EnableReplicationFilter;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import akka.javasdk.eventsourcedentity.ReplicationFilter;
import com.example.domain.ProbeReading;
import com.example.domain.RegionProbeEvent;
import com.example.domain.RegionProbeEvent.ProbeWritten;
import com.example.domain.RegionProbeState;

import java.time.Instant;

/**
 * Replication filter probe (spec 002, US8). The first write limits replication to the region that
 * handled it, so other regions should never see this entity's events (checklist R3).
 */
@Component(id = "filtered-probe")
@EnableReplicationFilter
public class FilteredProbeEntity extends EventSourcedEntity<RegionProbeState, RegionProbeEvent> {

  private final String entityId;

  public FilteredProbeEntity(EventSourcedEntityContext context) {
    this.entityId = context.entityId();
  }

  @Override
  public RegionProbeState emptyState() {
    return null;
  }

  public Effect<ProbeReading> write(RegionProbeEntity.WriteCommand command) {
    var region = commandContext().selfRegion();
    var seq = currentState() == null ? 1 : currentState().seq() + 1;
    var event = new ProbeWritten(command.value(), seq, false, region, Instant.now());
    var persist = effects().persist(event);
    if (currentState() == null) {
      persist = persist.updateReplicationFilter(ReplicationFilter.includeRegion(region));
    }
    return persist.thenReply(state -> new ProbeReading(state, region));
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
      case ProbeWritten written -> new RegionProbeState(
          entityId, written.value(), written.seq(), written.writtenIn(), written.writtenAt(), null, null);
    };
  }
}
