package com.example.api;

import akka.NotUsed;
import akka.javasdk.JsonSupport;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.WebSocket;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import com.example.application.WebSocketConnectionTracker;
import com.example.domain.WebSocketStats;

import java.time.Duration;

/**
 * WebSocket probes for measuring how many connections one service instance can hold in parallel.
 * Two flows are exposed: an echo flow (client-driven, round-trip latency) and a ticker flow
 * (server push). Connection counters are per instance; a load test against a multi-instance
 * service must sum the stats reported by each instance id.
 */
@HttpEndpoint("/pulse/ws")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class WebSocketEndpoint {

  public record WebSocketStatsResponse(
      String instanceId,
      int openConnections,
      int peakOpenConnections,
      long totalOpened,
      long totalClosed,
      long messagesReceived,
      long messagesSent) {

    static WebSocketStatsResponse toApi(WebSocketStats stats) {
      return new WebSocketStatsResponse(stats.instanceId(), stats.openConnections(),
          stats.peakOpenConnections(), stats.totalOpened(), stats.totalClosed(),
          stats.messagesReceived(), stats.messagesSent());
    }
  }

  /** One server-push message from the ticker flow. */
  public record Tick(long seq, String instanceId, int openConnections, long serverTimeMillis) {}

  private static final int MIN_INTERVAL_SECONDS = 1;
  private static final int MAX_INTERVAL_SECONDS = 60;

  private final WebSocketConnectionTracker tracker;

  public WebSocketEndpoint(WebSocketConnectionTracker tracker) {
    this.tracker = tracker;
  }

  /** Echoes every text message back unchanged. Use it to hold connections and measure RTT. */
  @WebSocket("/echo")
  public Flow<String, String, NotUsed> echo() {
    tracker.connectionOpened();
    return Flow.of(String.class)
        .map(message -> {
          tracker.messageReceived();
          tracker.messageSent();
          return message;
        })
        .watchTermination(this::onTermination);
  }

  /**
   * Pushes one JSON {@link Tick} every {intervalSeconds} (clamped to 1..60) and ignores client
   * messages. Closing either side closes the connection.
   */
  @WebSocket("/ticker/{intervalSeconds}")
  public Flow<String, String, NotUsed> ticker(int intervalSeconds) {
    var seconds = Math.max(MIN_INTERVAL_SECONDS, Math.min(MAX_INTERVAL_SECONDS, intervalSeconds));
    var interval = Duration.ofSeconds(seconds);
    tracker.connectionOpened();
    var instanceId = tracker.snapshot().instanceId();
    var ticks = Source.tick(Duration.ZERO, interval, NotUsed.getInstance())
        .zipWithIndex()
        .map(pair -> {
          tracker.messageSent();
          var tick = new Tick(pair.second(), instanceId, tracker.openConnections(),
              System.currentTimeMillis());
          return JsonSupport.encodeToString(tick);
        })
        .mapMaterializedValue(cancellable -> NotUsed.getInstance());
    return Flow.fromSinkAndSourceCoupled(Sink.<String>ignore(), ticks)
        .watchTermination(this::onTermination);
  }

  @Get("/stats")
  public WebSocketStatsResponse stats() {
    return WebSocketStatsResponse.toApi(tracker.snapshot());
  }

  /** Clears peak and totals so a new load run starts from zero. Open connections stay counted. */
  @Post("/stats/reset")
  public WebSocketStatsResponse reset() {
    tracker.reset();
    return WebSocketStatsResponse.toApi(tracker.snapshot());
  }

  private NotUsed onTermination(NotUsed notUsed, java.util.concurrent.CompletionStage<akka.Done> done) {
    done.whenComplete((ignored, failure) -> tracker.connectionClosed());
    return NotUsed.getInstance();
  }
}
