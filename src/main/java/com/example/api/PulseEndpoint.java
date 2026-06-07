package com.example.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import akka.javasdk.timer.TimerScheduler;
import akka.Done;
import akka.javasdk.annotations.http.Delete;
import com.example.application.ConsumerCounterEntity;
import com.example.application.HealthCheckEntity;
import com.example.application.SyntheticEntryEntity;
import com.example.application.SyntheticRecordEntity;
import com.example.application.SyntheticRecordView;
import com.example.domain.ConsumerCounter;
import com.example.domain.SyntheticEntry;
import com.example.application.SyntheticTimedAction;
import com.example.domain.SyntheticRecord;

import java.time.Duration;
import java.time.Instant;

@HttpEndpoint("/pulse")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class PulseEndpoint {

  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;

  public PulseEndpoint(ComponentClient componentClient, TimerScheduler timerScheduler) {
    this.componentClient = componentClient;
    this.timerScheduler = timerScheduler;
  }

  // --- Health Check (US1) ---

  public record PersistenceCheckResult(String status, long latencyMs) {}
  public record PersistenceCheckError(String status, String error) {}
  public record HealthUpResponse(String status, String serviceName, String version, Instant timestamp, PersistenceCheckResult persistenceCheck) {}
  public record HealthDownResponse(String status, String serviceName, String version, Instant timestamp, PersistenceCheckError persistenceCheck) {}

  @Get("/health")
  public HealthUpResponse health() {
    long start = System.currentTimeMillis();
    componentClient.forKeyValueEntity("heartbeat")
        .method(HealthCheckEntity::set)
        .invoke();

    componentClient.forKeyValueEntity("heartbeat")
        .method(HealthCheckEntity::get)
        .invoke();

    long latencyMs = System.currentTimeMillis() - start;
    return new HealthUpResponse(
        "UP", "akka-pulse", "1.0-SNAPSHOT", Instant.now(),
        new PersistenceCheckResult("OK", latencyMs));
  }

  // --- Event Sourced Entity (US2) ---

  public record CreateRecordRequest(String name, String value, int delaySeconds) {}
  public record UpdateRecordRequest(String value, int delaySeconds) {}

  @Post("/ese/{recordId}/create")
  public HttpResponse createRecord(String recordId, CreateRecordRequest request) {
    var command = new SyntheticRecordEntity.CreateCommand(request.name(), request.value(), request.delaySeconds());
    var result = componentClient.forEventSourcedEntity(recordId)
        .method(SyntheticRecordEntity::create)
        .invoke(command);
    return HttpResponses.created(result);
  }

  @Post("/ese/{recordId}/update")
  public SyntheticRecord updateRecord(String recordId, UpdateRecordRequest request) {
    var command = new SyntheticRecordEntity.UpdateCommand(request.value(), request.delaySeconds());
    return componentClient.forEventSourcedEntity(recordId)
        .method(SyntheticRecordEntity::update)
        .invoke(command);
  }

  @Get("/ese/{recordId}")
  public SyntheticRecord getRecord(String recordId) {
    return componentClient.forEventSourcedEntity(recordId)
        .method(SyntheticRecordEntity::get)
        .invoke();
  }

  // --- Key Value Entity (US3) ---

  public record SetEntryRequest(String data, int delaySeconds) {}

  @Post("/kve/{entryId}")
  public SyntheticEntry setEntry(String entryId, SetEntryRequest request) {
    var command = new SyntheticEntryEntity.SetCommand(request.data(), request.delaySeconds());
    return componentClient.forKeyValueEntity(entryId)
        .method(SyntheticEntryEntity::set)
        .invoke(command);
  }

  @Get("/kve/{entryId}")
  public SyntheticEntry getEntry(String entryId) {
    return componentClient.forKeyValueEntity(entryId)
        .method(SyntheticEntryEntity::get)
        .invoke();
  }

  @Delete("/kve/{entryId}")
  public Done deleteEntry(String entryId) {
    return componentClient.forKeyValueEntity(entryId)
        .method(SyntheticEntryEntity::delete)
        .invoke();
  }

  // --- View (US4) ---

  @Get("/view/by-name/{name}")
  public SyntheticRecordView.SyntheticRecordEntries getRecordsByName(String name) {
    return componentClient.forView()
        .method(SyntheticRecordView::getByName)
        .invoke(name);
  }

  @Get("/view/all")
  public SyntheticRecordView.SyntheticRecordEntries getAllRecords() {
    return componentClient.forView()
        .method(SyntheticRecordView::getAll)
        .invoke();
  }

  // --- Consumer Counter (US6) ---

  @Get("/consumers/{counterId}")
  public ConsumerCounter getConsumerCounter(String counterId) {
    return componentClient.forKeyValueEntity(counterId)
        .method(ConsumerCounterEntity::get)
        .invoke();
  }

  // --- Timed Action (US7) ---

  public record ScheduleTimerRequest(int delaySeconds) {}
  public record TimerScheduledResponse(String timerId, int delaySeconds) {}

  @Post("/timers/{timerId}/schedule")
  public TimerScheduledResponse scheduleTimer(String timerId, ScheduleTimerRequest request) {
    var deferred = componentClient.forTimedAction()
        .method(SyntheticTimedAction::execute)
        .deferred();
    timerScheduler.createSingleTimer(timerId, Duration.ofSeconds(request.delaySeconds()), deferred);
    return new TimerScheduledResponse(timerId, request.delaySeconds());
  }

  @Get("/timers/{timerId}")
  public ConsumerCounter getTimerStatus(String timerId) {
    return componentClient.forKeyValueEntity("timer-counter")
        .method(ConsumerCounterEntity::get)
        .invoke();
  }

  // --- OpenAPI (US8) ---

  @Get("/openapi.yaml")
  public HttpResponse openApiYaml() {
    return HttpResponses.staticResource("openapi.yaml");
  }

  @Get("/docs")
  public HttpResponse swaggerUi() {
    return HttpResponses.staticResource("swagger-ui.html");
  }
}
