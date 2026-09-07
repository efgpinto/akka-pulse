package com.example.peer.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpClientProvider;
import com.example.common.api.HealthEndpoint;
import com.example.peer.application.StreamCounterEntity;

import java.time.Instant;

/**
 * Service-to-service validation probes against pulse-core. Each probe returns an explicit
 * pass/fail with evidence and never a 5xx: a failing probe is a result, not an error.
 */
@HttpEndpoint("/peer/probes")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class PeerProbeEndpoint {

  private static final String TARGET_SERVICE = "pulse-core";

  public record DirectProbeResult(String probe, boolean passed, String target, String evidence) {}
  public record StreamProbeResult(String probe, boolean passed, long consumedCount, Instant lastEventAt) {}
  public record RestrictedProbeResult(String probe, boolean passed, String target, String evidence) {}

  // Local copy of InternalPingEndpoint.PingResponse: the peer depends only on pulse-common,
  // not on pulse-core, so the wire shape is duplicated here on purpose.
  public record CorePing(String serviceName, String region, Instant timestamp) {}

  private final ComponentClient componentClient;
  private final HttpClientProvider httpClientProvider;

  public PeerProbeEndpoint(ComponentClient componentClient, HttpClientProvider httpClientProvider) {
    this.componentClient = componentClient;
    this.httpClientProvider = httpClientProvider;
  }

  @Get("/direct")
  public DirectProbeResult direct() {
    try {
      var response = httpClientProvider.httpClientFor(TARGET_SERVICE)
          .GET("/pulse/health")
          .responseBodyAs(HealthEndpoint.HealthUpResponse.class)
          .invoke();
      if (response.status().isSuccess()) {
        var body = response.body();
        return new DirectProbeResult("direct", true, TARGET_SERVICE,
            body.status() + " region=" + body.region());
      }
      return new DirectProbeResult("direct", false, TARGET_SERVICE,
          "Unexpected status: " + response.status());
    } catch (Exception e) {
      return new DirectProbeResult("direct", false, TARGET_SERVICE, errorEvidence(e));
    }
  }

  @Get("/stream")
  public StreamProbeResult stream() {
    var counter = componentClient.forKeyValueEntity("synthetic-records")
        .method(StreamCounterEntity::get)
        .invoke();
    return new StreamProbeResult("stream", counter.count() > 0, counter.count(), counter.lastEventAt());
  }

  @Get("/restricted")
  public RestrictedProbeResult restricted() {
    try {
      var response = httpClientProvider.httpClientFor(TARGET_SERVICE)
          .GET("/pulse/internal/ping")
          .responseBodyAs(CorePing.class)
          .invoke();
      if (response.status().isSuccess()) {
        var body = response.body();
        return new RestrictedProbeResult("restricted", true, TARGET_SERVICE,
            "pong from " + body.serviceName() + " region=" + body.region());
      }
      return new RestrictedProbeResult("restricted", false, TARGET_SERVICE,
          "Unexpected status: " + response.status());
    } catch (Exception e) {
      return new RestrictedProbeResult("restricted", false, TARGET_SERVICE, errorEvidence(e));
    }
  }

  private static String errorEvidence(Exception e) {
    var message = e.getMessage();
    return e.getClass().getSimpleName() + (message != null ? ": " + message : "");
  }
}
