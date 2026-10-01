package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.EnableReplicationFilter;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import akka.javasdk.keyvalueentity.ReplicationFilter;
import com.example.domain.ProbeReading;
import com.example.domain.RegionProbeState;

import java.time.Instant;

/** Key Value Entity counterpart of {@link FilteredProbeEntity} (spec 002, US8). */
@Component(id = "filtered-probe-kve")
@EnableReplicationFilter
public class FilteredProbeKve extends KeyValueEntity<RegionProbeState> {

  public Effect<ProbeReading> write(RegionProbeKve.WriteCommand command) {
    var region = commandContext().selfRegion();
    var seq = currentState() == null ? 1 : currentState().seq() + 1;
    var state = new RegionProbeState(
        commandContext().entityId(), command.value(), seq, region, Instant.now(), null, null);
    var update = effects().updateState(state);
    if (currentState() == null) {
      update = update.updateReplicationFilter(ReplicationFilter.includeRegion(region));
    }
    return update.thenReply(new ProbeReading(state, region));
  }

  public Effect<ProbeReading> readPlain() {
    return effects().reply(new ProbeReading(currentState(), commandContext().selfRegion()));
  }

  public ReadOnlyEffect<ProbeReading> readOnly() {
    return effects().reply(new ProbeReading(currentState(), commandContext().selfRegion()));
  }
}
