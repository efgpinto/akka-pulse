package com.example.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.timer.TimerScheduler;
import com.example.application.FilteredProbeEntity;
import com.example.application.FilteredProbeKve;
import com.example.application.FilteredProbeView;
import com.example.application.ProbeLedgerEntity;
import com.example.application.RegionProbeEntity;
import com.example.application.RegionProbeKve;
import com.example.application.RegionProbeTimedAction;
import com.example.application.RegionProbeView;
import com.example.application.RegionProbeWorkflow;
import com.example.domain.ProbeLedger;
import com.example.domain.ProbeReading;

import java.time.Duration;

/**
 * Multi-region assumption probes (spec 002, US8). Each call runs in the region that serves the
 * request; component replies name the region where the component handler ran.
 */
@HttpEndpoint("/pulse/probe")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class RegionProbeEndpoint extends AbstractHttpEndpoint {

  public record WhoAmI(String region) {}
  public record WriteRequest(String value, boolean withTimer) {}
  public record TimerRequest(int delaySeconds) {}
  public record TimerScheduled(String timerName, String scheduledIn) {}

  private final ComponentClient componentClient;
  private final TimerScheduler timerScheduler;

  public RegionProbeEndpoint(ComponentClient componentClient, TimerScheduler timerScheduler) {
    this.componentClient = componentClient;
    this.timerScheduler = timerScheduler;
  }

  private static ProbeReading timed(java.util.function.Supplier<ProbeReading> call) {
    var start = System.nanoTime();
    var reading = call.get();
    return reading.withCallMicros((System.nanoTime() - start) / 1000);
  }

  private static RegionProbeWorkflow.ProbeWorkflowReply timedWorkflow(
      java.util.function.Supplier<RegionProbeWorkflow.ProbeWorkflowReply> call) {
    var start = System.nanoTime();
    var reply = call.get();
    return reply.withCallMicros((System.nanoTime() - start) / 1000);
  }

  @Get("/whoami")
  public WhoAmI whoAmI() {
    return new WhoAmI(requestContext().selfRegion());
  }

  // --- Event Sourced Entity ---

  @Post("/ese/{id}")
  public ProbeReading writeEse(String id, WriteRequest request) {
    return timed(() -> componentClient.forEventSourcedEntity(id)
        .method(RegionProbeEntity::write)
        .invoke(new RegionProbeEntity.WriteCommand(request.value(), request.withTimer())));
  }

  @Get("/ese/{id}/plain")
  public ProbeReading readEsePlain(String id) {
    return timed(() -> componentClient.forEventSourcedEntity(id).method(RegionProbeEntity::readPlain).invoke());
  }

  @Get("/ese/{id}/read-only")
  public ProbeReading readEseReadOnly(String id) {
    return timed(() -> componentClient.forEventSourcedEntity(id).method(RegionProbeEntity::readOnly).invoke());
  }

  // --- Key Value Entity ---

  @Post("/kve/{id}")
  public ProbeReading writeKve(String id, WriteRequest request) {
    return timed(() -> componentClient.forKeyValueEntity(id)
        .method(RegionProbeKve::write)
        .invoke(new RegionProbeKve.WriteCommand(request.value())));
  }

  @Get("/kve/{id}/plain")
  public ProbeReading readKvePlain(String id) {
    return timed(() -> componentClient.forKeyValueEntity(id).method(RegionProbeKve::readPlain).invoke());
  }

  @Get("/kve/{id}/read-only")
  public ProbeReading readKveReadOnly(String id) {
    return timed(() -> componentClient.forKeyValueEntity(id).method(RegionProbeKve::readOnly).invoke());
  }

  // --- Replication filters ---

  @Post("/filtered-ese/{id}")
  public ProbeReading writeFilteredEse(String id, WriteRequest request) {
    return timed(() -> componentClient.forEventSourcedEntity(id)
        .method(FilteredProbeEntity::write)
        .invoke(new RegionProbeEntity.WriteCommand(request.value(), false)));
  }

  @Get("/filtered-ese/{id}/plain")
  public ProbeReading readFilteredEsePlain(String id) {
    return timed(() -> componentClient.forEventSourcedEntity(id).method(FilteredProbeEntity::readPlain).invoke());
  }

  @Get("/filtered-ese/{id}/read-only")
  public ProbeReading readFilteredEseReadOnly(String id) {
    return timed(() -> componentClient.forEventSourcedEntity(id).method(FilteredProbeEntity::readOnly).invoke());
  }

  @Post("/filtered-kve/{id}")
  public ProbeReading writeFilteredKve(String id, WriteRequest request) {
    return timed(() -> componentClient.forKeyValueEntity(id)
        .method(FilteredProbeKve::write)
        .invoke(new RegionProbeKve.WriteCommand(request.value())));
  }

  @Get("/filtered-kve/{id}/plain")
  public ProbeReading readFilteredKvePlain(String id) {
    return timed(() -> componentClient.forKeyValueEntity(id).method(FilteredProbeKve::readPlain).invoke());
  }

  @Get("/filtered-kve/{id}/read-only")
  public ProbeReading readFilteredKveReadOnly(String id) {
    return timed(() -> componentClient.forKeyValueEntity(id).method(FilteredProbeKve::readOnly).invoke());
  }

  // --- Views ---

  @Get("/view/{id}")
  public RegionProbeView.ProbeRows view(String id) {
    return componentClient.forView().method(RegionProbeView::getById).invoke(id);
  }

  @Get("/filtered-view/{id}")
  public FilteredProbeView.FilteredRows filteredView(String id) {
    return componentClient.forView().method(FilteredProbeView::getById).invoke(id);
  }

  // --- Workflow ---

  @Post("/workflow/{id}/start")
  public RegionProbeWorkflow.ProbeWorkflowReply startWorkflow(
      String id, RegionProbeWorkflow.StartCommand request) {
    return timedWorkflow(() -> componentClient.forWorkflow(id).method(RegionProbeWorkflow::start).invoke(request));
  }

  @Post("/workflow/{id}/signal")
  public RegionProbeWorkflow.ProbeWorkflowReply signalWorkflow(String id) {
    return timedWorkflow(() -> componentClient.forWorkflow(id).method(RegionProbeWorkflow::signal).invoke());
  }

  @Get("/workflow/{id}")
  public RegionProbeWorkflow.ProbeWorkflowReply workflowStatus(String id) {
    return timedWorkflow(() -> componentClient.forWorkflow(id).method(RegionProbeWorkflow::status).invoke());
  }

  // --- Timers and ledger ---

  /** Baseline for R4: a timer scheduled once, from the region that serves this request. */
  @Post("/timer/{name}")
  public TimerScheduled scheduleTimer(String name, TimerRequest request) {
    var call = componentClient.forTimedAction()
        .method(RegionProbeTimedAction::fire)
        .deferred("timer-endpoint");
    timerScheduler.createSingleTimer(name, Duration.ofSeconds(request.delaySeconds()), call);
    return new TimerScheduled(name, requestContext().selfRegion());
  }

  @Get("/ledger/{ledgerId}")
  public ProbeLedger ledger(String ledgerId) {
    return componentClient.forKeyValueEntity(ledgerId).method(ProbeLedgerEntity::get).invoke();
  }
}
