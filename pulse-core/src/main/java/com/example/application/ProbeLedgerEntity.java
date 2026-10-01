package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import com.example.domain.ProbeLedger;

import java.time.Instant;

/**
 * Records probe side effects (spec 002, US8). Callers key the ledger by region, for example
 * {@code consumer-unguarded-aws-us-east-2}, so each ledger is only written from one region and never
 * switches primary.
 */
@Component(id = "probe-ledger")
public class ProbeLedgerEntity extends KeyValueEntity<ProbeLedger> {

  public record RecordCommand(String originRegion, boolean hasLocalOrigin, String selfRegion) {}

  public Effect<ProbeLedger> record(RecordCommand command) {
    var state = currentState() != null ? currentState() : ProbeLedger.empty(commandContext().entityId());
    var updated = state.record(
        command.originRegion(), command.hasLocalOrigin(), command.selfRegion(), Instant.now());
    return effects().updateState(updated).thenReply(updated);
  }

  public ReadOnlyEffect<ProbeLedger> get() {
    if (currentState() == null) {
      return effects().reply(ProbeLedger.empty(commandContext().entityId()));
    }
    return effects().reply(currentState());
  }
}
