package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import com.example.domain.SyntheticRecordEvent;
import com.example.domain.SyntheticRecordEvent.RecordCreated;
import com.example.domain.SyntheticRecordEvent.RecordUpdated;

import java.util.List;

@Component(id = "synthetic-record-view")
public class SyntheticRecordView extends View {

  public record SyntheticRecordEntry(String recordId, String name, String value, String status) {
    public SyntheticRecordEntry withValue(String newValue) {
      return new SyntheticRecordEntry(recordId, name, newValue, "UPDATED");
    }
  }
  public record SyntheticRecordEntries(List<SyntheticRecordEntry> entries) {}

  @Consume.FromEventSourcedEntity(SyntheticRecordEntity.class)
  public static class SyntheticRecordUpdater extends TableUpdater<SyntheticRecordEntry> {

    public Effect<SyntheticRecordEntry> onEvent(SyntheticRecordEvent event) {
      var entityId = updateContext().eventSubject().orElse("");
      return switch (event) {
        case RecordCreated created -> effects()
            .updateRow(new SyntheticRecordEntry(entityId, created.name(), created.value(), "CREATED"));
        case RecordUpdated updated -> effects()
            .updateRow(rowState().withValue(updated.value()));
      };
    }
  }

  @Query("SELECT * AS entries FROM synthetic_records WHERE name = :name")
  public QueryEffect<SyntheticRecordEntries> getByName(String name) {
    return queryResult();
  }

  @Query("SELECT * AS entries FROM synthetic_records")
  public QueryEffect<SyntheticRecordEntries> getAll() {
    return queryResult();
  }
}
