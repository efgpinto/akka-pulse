package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import com.example.domain.RegionProbeEvent;
import com.example.domain.RegionProbeEvent.ProbeWritten;

import java.util.List;

/** Shows whether events of a filtered entity reach the View in other regions (checklist R3). */
@Component(id = "filtered-probe-view")
public class FilteredProbeView extends View {

  public record FilteredRow(String probeId, String value, int seq, String writtenIn, String builtIn) {}

  public record FilteredRows(List<FilteredRow> entries) {}

  @Consume.FromEventSourcedEntity(FilteredProbeEntity.class)
  public static class FilteredUpdater extends TableUpdater<FilteredRow> {

    public Effect<FilteredRow> onEvent(RegionProbeEvent event) {
      var context = updateContext();
      return switch (event) {
        case ProbeWritten written -> effects().updateRow(new FilteredRow(
            context.eventSubject().orElse(""), written.value(), written.seq(), written.writtenIn(),
            context.selfRegion()));
      };
    }
  }

  @Query("SELECT * AS entries FROM filtered_probes WHERE probeId = :probeId")
  public QueryEffect<FilteredRows> getById(String probeId) {
    return queryResult();
  }
}
