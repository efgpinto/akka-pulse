package com.example.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpClientProvider;

/**
 * Outbound connectivity probe for validating project egress network policy rules. Calls an
 * arbitrary external HTTP server and returns an explicit pass/fail with evidence, never a 5xx:
 * a blocked connection is a result, not an error.
 */
@HttpEndpoint("/pulse/probes")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class EgressProbeEndpoint extends AbstractHttpEndpoint {

  public record EgressProbeResult(String probe, boolean passed, String target, String evidence) {}

  private final HttpClientProvider httpClientProvider;

  public EgressProbeEndpoint(HttpClientProvider httpClientProvider) {
    this.httpClientProvider = httpClientProvider;
  }

  /**
   * Probes http://{host}:{port}{path}. Query parameters: host (default portquiz.net),
   * port (default 80), path (default /).
   */
  @Get("/egress")
  public EgressProbeResult egress() {
    var params = requestContext().queryParams();
    var host = params.getString("host").orElse("portquiz.net");
    var port = params.getInteger("port").orElse(80);
    var path = params.getString("path").orElse("/");
    var target = "http://" + host + ":" + port + path;
    try {
      var response = httpClientProvider.httpClientFor("http://" + host + ":" + port)
          .GET(path)
          .responseBodyAs(String.class)
          .invoke();
      var body = response.body() == null ? "" : response.body().strip();
      var excerpt = body.length() > 120 ? body.substring(0, 120) + "..." : body;
      return new EgressProbeResult("egress", response.status().isSuccess(), target,
          response.status() + " " + excerpt);
    } catch (Exception e) {
      var message = e.getMessage();
      return new EgressProbeResult("egress", false, target,
          e.getClass().getSimpleName() + (message != null ? ": " + message : ""));
    }
  }
}
