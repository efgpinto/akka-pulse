package com.example.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class WebSocketEndpointIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withDisabledComponents(Set.of(JwtEndpoint.class));
  }

  @Test
  public void echoReturnsEachMessageUnchanged() {
    var connection = testKit.getSelfWebSocketRouteTester().wsTextConnection("/pulse/ws/echo");
    var publisher = connection.publisher();
    var subscriber = connection.subscriber();

    subscriber.request(2);
    publisher.sendNext("ping 1");
    assertThat(subscriber.expectNext()).isEqualTo("ping 1");
    publisher.sendNext("ping 2");
    assertThat(subscriber.expectNext()).isEqualTo("ping 2");

    publisher.sendComplete();
    subscriber.expectComplete();
  }

  @Test
  public void tickerPushesSequencedTicksWithoutClientMessages() {
    var connection = testKit.getSelfWebSocketRouteTester().wsTextConnection("/pulse/ws/ticker/1");
    var subscriber = connection.subscriber();

    subscriber.request(2);
    var first = subscriber.expectNext();
    var second = subscriber.expectNext();

    assertThat(first).contains("\"seq\":0").contains("\"instanceId\"").contains("\"openConnections\"");
    assertThat(second).contains("\"seq\":1");

    connection.publisher().sendComplete();
    Awaitility.await().atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(stats().openConnections()).isEqualTo(0));
  }

  @Test
  public void statsTrackOpenAndClosedConnections() {
    var before = stats();

    var connection = testKit.getSelfWebSocketRouteTester().wsTextConnection("/pulse/ws/echo");
    connection.subscriber().request(1);
    connection.publisher().sendNext("hello");
    assertThat(connection.subscriber().expectNext()).isEqualTo("hello");

    var during = stats();
    assertThat(during.openConnections()).isEqualTo(before.openConnections() + 1);
    assertThat(during.totalOpened()).isEqualTo(before.totalOpened() + 1);
    assertThat(during.peakOpenConnections()).isGreaterThanOrEqualTo(during.openConnections());
    assertThat(during.messagesReceived()).isEqualTo(before.messagesReceived() + 1);
    assertThat(during.messagesSent()).isEqualTo(before.messagesSent() + 1);
    assertThat(during.instanceId()).isNotBlank();

    connection.publisher().sendComplete();
    connection.subscriber().expectComplete();

    Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      var after = stats();
      assertThat(after.openConnections()).isEqualTo(before.openConnections());
      assertThat(after.totalClosed()).isEqualTo(before.totalClosed() + 1);
    });
  }

  @Test
  public void resetClearsTotalsButKeepsOpenConnections() {
    var connection = testKit.getSelfWebSocketRouteTester().wsTextConnection("/pulse/ws/echo");
    connection.subscriber().request(1);
    connection.publisher().sendNext("x");
    assertThat(connection.subscriber().expectNext()).isEqualTo("x");
    assertThat(stats().totalOpened()).isGreaterThan(0);

    var response = httpClient.POST("/pulse/ws/stats/reset")
        .responseBodyAs(WebSocketEndpoint.WebSocketStatsResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().totalOpened()).isEqualTo(0);
    assertThat(response.body().totalClosed()).isEqualTo(0);
    assertThat(response.body().messagesReceived()).isEqualTo(0);
    assertThat(response.body().openConnections()).isGreaterThanOrEqualTo(1);
    assertThat(response.body().peakOpenConnections()).isEqualTo(response.body().openConnections());

    connection.publisher().sendComplete();
    connection.subscriber().expectComplete();
  }

  private WebSocketEndpoint.WebSocketStatsResponse stats() {
    return httpClient.GET("/pulse/ws/stats")
        .responseBodyAs(WebSocketEndpoint.WebSocketStatsResponse.class)
        .invoke()
        .body();
  }
}
