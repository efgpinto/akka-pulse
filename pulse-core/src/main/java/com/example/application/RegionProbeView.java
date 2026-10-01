package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import com.example.domain.RegionProbeEvent;
import com.example.domain.RegionProbeEvent.ProbeWritten;

import java.time.Instant;
import java.util.List;

/**
 * View probe (spec 002, US8, checklist R2). {@code builtIn} and {@code viewUpdatedAt} are computed
 * in the updater, so they are region-local on purpose. The other fields come from the event.
 */
@Component(id = "region-probe-view")
public class RegionProbeView extends View {

  public record ProbeRow(
      String probeId,
      String value,
      int seq,
      String writtenIn,
      Instant writtenAt,
      String builtIn,
      String originRegion,
      boolean hasLocalOrigin,
      Instant viewUpdatedAt) {}

  public record ProbeRows(List<ProbeRow> entries) {}

  @Consume.FromEventSourcedEntity(RegionProbeEntity.class)
  public static class ProbeUpdater extends TableUpdater<ProbeRow> {

    public Effect<ProbeRow> onEvent(RegionProbeEvent event) {
      var context = updateContext();
      return switch (event) {
        case ProbeWritten written -> effects().updateRow(new ProbeRow(
            context.eventSubject().orElse(""), written.value(), written.seq(), written.writtenIn(),
            written.writtenAt(), context.selfRegion(), context.originRegion().orElse(""),
            context.hasLocalOrigin(), Instant.now()));
      };
    }
  }

  @Query("SELECT * AS entries FROM region_probes WHERE probeId = :probeId")
  public QueryEffect<ProbeRows> getById(String probeId) {
    return queryResult();
  }
}
