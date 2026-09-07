package com.example.common.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.example.common.application.HealthCheckEntity;
import com.typesafe.config.Config;

import java.time.Instant;

/**
 * Reusable deep health check served by every service that has pulse-common on its classpath.
 * Performs a write/read persistence round trip through {@link HealthCheckEntity} and reports
 * the region the write landed in. The serving service identifies itself via the
 * {@code pulse.health.service-name} config key (each service sets it in application.conf).
 */
// Allows the internet AND sibling services: an internet-only ACL rejects s2s callers, which
// carry a service principal (found by pulse-peer's direct probe on first deployment).
@HttpEndpoint
@Acl(allow = { @Acl.Matcher(principal = Acl.Principal.INTERNET), @Acl.Matcher(service = "*") })
public class HealthEndpoint {

  public record PersistenceCheckResult(String status, long latencyMs) {}
  public record PersistenceCheckError(String status, String error) {}
  public record HealthUpResponse(String status, String serviceName, String version, String region, Instant timestamp, PersistenceCheckResult persistenceCheck) {}
  public record HealthDownResponse(String status, String serviceName, String version, String region, Instant timestamp, PersistenceCheckError persistenceCheck) {}

  private final ComponentClient componentClient;
  private final String serviceName;
  private final String version;

  public HealthEndpoint(ComponentClient componentClient, Config config) {
    this.componentClient = componentClient;
    this.serviceName = config.getString("pulse.health.service-name");
    this.version = config.getString("pulse.health.version");
  }

  @Get("/pulse/health")
  public HttpResponse health() {
    long start = System.currentTimeMillis();
    try {
      componentClient.forKeyValueEntity("heartbeat")
          .method(HealthCheckEntity::set)
          .invoke();

      var entry = componentClient.forKeyValueEntity("heartbeat")
          .method(HealthCheckEntity::get)
          .invoke();

      long latencyMs = System.currentTimeMillis() - start;
      return HttpResponses.ok(new HealthUpResponse(
          "UP", serviceName, version, entry.region(), Instant.now(),
          new PersistenceCheckResult("OK", latencyMs)));
    } catch (Exception e) {
      var body = new HealthDownResponse(
          "DOWN", serviceName, version, "unknown", Instant.now(),
          new PersistenceCheckError("FAILED", "Persistence round-trip failed: " + e.getMessage()));
      return HttpResponse.create()
          .withStatus(akka.http.javadsl.model.StatusCodes.SERVICE_UNAVAILABLE)
          .withEntity(akka.http.javadsl.model.ContentTypes.APPLICATION_JSON,
              akka.javasdk.JsonSupport.encodeToAkkaByteString(body));
    }
  }
}
