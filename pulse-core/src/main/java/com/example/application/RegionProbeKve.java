package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import com.example.domain.ProbeReading;
import com.example.domain.RegionProbeState;

import java.time.Instant;

/** Key Value Entity counterpart of {@link RegionProbeEntity} (spec 002, US8). */
@Component(id = "region-probe-kve")
public class RegionProbeKve extends KeyValueEntity<RegionProbeState> {

  public record WriteCommand(String value) {}

  public Effect<ProbeReading> write(WriteCommand command) {
    var region = commandContext().selfRegion();
    var seq = currentState() == null ? 1 : currentState().seq() + 1;
    var state = new RegionProbeState(
        commandContext().entityId(), command.value(), seq, region, Instant.now(), null, null);
    return effects().updateState(state).thenReply(new ProbeReading(state, region));
  }

  public Effect<ProbeReading> readPlain() {
    return effects().reply(new ProbeReading(currentState(), commandContext().selfRegion()));
  }

  public ReadOnlyEffect<ProbeReading> readOnly() {
    return effects().reply(new ProbeReading(currentState(), commandContext().selfRegion()));
  }
}
