package com.example.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import com.example.application.SyntheticEntryEntity;
import com.example.application.SyntheticRecordEntity;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@HttpEndpoint("/pulse/burst")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class BurstEndpoint {

  public record BurstRequest(String target, int count, int delaySeconds) {}
  public record BurstResponse(String target, int requested, int succeeded, int failed, long totalDurationMs) {}

  private final ComponentClient componentClient;

  public BurstEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/")
  public BurstResponse burst(BurstRequest request) {
    if (request.count() < 1 || request.count() > 100) {
      throw new IllegalArgumentException("count must be between 1 and 100");
    }
    if (request.delaySeconds() < 0 || request.delaySeconds() > 300) {
      throw new IllegalArgumentException("delaySeconds must be between 0 and 300");
    }
    if (!"event-sourced-entity".equals(request.target()) && !"key-value-entity".equals(request.target())) {
      throw new IllegalArgumentException("target must be 'event-sourced-entity' or 'key-value-entity'");
    }

    long start = System.currentTimeMillis();
    var succeeded = new AtomicInteger(0);
    var failed = new AtomicInteger(0);

    var futures = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < request.count(); i++) {
      var entityId = "burst-" + UUID.randomUUID();
      var future = CompletableFuture.runAsync(() -> {
        try {
          if ("event-sourced-entity".equals(request.target())) {
            componentClient.forEventSourcedEntity(entityId)
                .method(SyntheticRecordEntity::create)
                .invoke(new SyntheticRecordEntity.CreateCommand("burst", "burst-value", request.delaySeconds()));
          } else {
            componentClient.forKeyValueEntity(entityId)
                .method(SyntheticEntryEntity::set)
                .invoke(new SyntheticEntryEntity.SetCommand("burst-data", request.delaySeconds()));
          }
          succeeded.incrementAndGet();
        } catch (Exception e) {
          failed.incrementAndGet();
        }
      });
      futures.add(future);
    }

    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    long totalDurationMs = System.currentTimeMillis() - start;

    return new BurstResponse(request.target(), request.count(), succeeded.get(), failed.get(), totalDurationMs);
  }
}
