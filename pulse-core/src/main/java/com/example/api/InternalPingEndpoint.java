package com.example.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.typesafe.config.Config;

import java.time.Instant;

/**
 * Service-to-service probe target: only the pulse-peer service is allowed to call this.
 * Requests from the internet or any other service are denied by the ACL (platform-enforced).
 */
@HttpEndpoint("/pulse/internal")
@Acl(allow = @Acl.Matcher(service = "pulse-peer"))
public class InternalPingEndpoint extends AbstractHttpEndpoint {

  public record PingResponse(String serviceName, String region, Instant timestamp) {}

  private final String serviceName;

  public InternalPingEndpoint(Config config) {
    this.serviceName = config.getString("pulse.health.service-name");
  }

  @Get("/ping")
  public PingResponse ping() {
    return new PingResponse(serviceName, requestContext().selfRegion(), Instant.now());
  }
}
