import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket load generator for the pulse-core WebSocket probes. Single-file program, JDK 21:
 *
 *   java tools/ws-load/WsLoad.java --url ws://localhost:9000/pulse/ws/echo \
 *       --connections 500 --concurrency 50 --hold 30 --ping-interval 5
 *
 * Opens N connections with at most --concurrency handshakes in flight, holds them for --hold
 * seconds, sends "ping <nanos>" on every open connection each --ping-interval seconds (0 disables
 * pings, use it for the ticker flow), then closes them all and prints a summary: how many opened,
 * how many failed and why, handshake latency, echo round-trip latency, and how many connections
 * the server closed on its own during the hold.
 */
public class WsLoad {

  static final Map<String, AtomicInteger> openFailures = new ConcurrentHashMap<>();
  static final Map<String, AtomicInteger> serverCloses = new ConcurrentHashMap<>();
  static final Map<String, AtomicInteger> errors = new ConcurrentHashMap<>();
  static final List<Long> connectNanos = new CopyOnWriteArrayList<>();
  static final List<Long> rttNanos = new CopyOnWriteArrayList<>();
  static final AtomicInteger opened = new AtomicInteger();
  static final AtomicInteger alive = new AtomicInteger();
  static final AtomicLong sent = new AtomicLong();
  static final AtomicLong received = new AtomicLong();
  static volatile boolean closing = false;

  public static void main(String[] args) throws Exception {
    var opts = parse(args);
    var url = URI.create(opts.getOrDefault("url", "ws://localhost:9000/pulse/ws/echo"));
    int connections = Integer.parseInt(opts.getOrDefault("connections", "100"));
    int concurrency = Integer.parseInt(opts.getOrDefault("concurrency", "50"));
    int holdSeconds = Integer.parseInt(opts.getOrDefault("hold", "30"));
    int pingInterval = Integer.parseInt(opts.getOrDefault("ping-interval", "5"));
    int timeoutSeconds = Integer.parseInt(opts.getOrDefault("timeout", "15"));

    System.out.printf("target=%s connections=%d concurrency=%d hold=%ds ping-interval=%ds timeout=%ds%n",
        url, connections, concurrency, holdSeconds, pingInterval, timeoutSeconds);

    var client = HttpClient.newBuilder()
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build();

    var sockets = new CopyOnWriteArrayList<WebSocket>();
    var inFlight = new Semaphore(concurrency);
    long openStart = System.nanoTime();
    var lastProgress = new AtomicLong(openStart);

    for (int i = 0; i < connections; i++) {
      inFlight.acquire();
      long started = System.nanoTime();
      client.newWebSocketBuilder()
          .connectTimeout(Duration.ofSeconds(timeoutSeconds))
          .buildAsync(url, new Listener())
          .whenComplete((ws, failure) -> {
            inFlight.release();
            if (failure != null) {
              openFailures.computeIfAbsent(describe(failure), k -> new AtomicInteger()).incrementAndGet();
            } else {
              connectNanos.add(System.nanoTime() - started);
              opened.incrementAndGet();
              alive.incrementAndGet();
              sockets.add(ws);
            }
          });
      long now = System.nanoTime();
      if (now - lastProgress.get() > TimeUnit.SECONDS.toNanos(2)) {
        lastProgress.set(now);
        System.out.printf("  opening... attempted=%d opened=%d failed=%d%n", i + 1, opened.get(), failed());
      }
    }
    inFlight.acquire(concurrency);
    long openMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - openStart);
    System.out.printf("open phase: opened=%d failed=%d in %d ms (%.0f conn/s)%n",
        opened.get(), failed(), openMillis, opened.get() * 1000.0 / Math.max(1, openMillis));

    long holdStart = System.nanoTime();
    long nextPing = holdStart;
    long nextReport = holdStart + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() - holdStart < TimeUnit.SECONDS.toNanos(holdSeconds)) {
      long now = System.nanoTime();
      if (pingInterval > 0 && now >= nextPing) {
        nextPing = now + TimeUnit.SECONDS.toNanos(pingInterval);
        for (var ws : sockets) {
          if (!ws.isOutputClosed()) {
            ws.sendText("ping " + System.nanoTime(), true)
                .whenComplete((w, f) -> { if (f == null) sent.incrementAndGet(); });
          }
        }
      }
      if (now >= nextReport) {
        nextReport = now + TimeUnit.SECONDS.toNanos(5);
        System.out.printf("  holding... alive=%d serverClosed=%d sent=%d received=%d rtt p50=%s%n",
            alive.get(), count(serverCloses), sent.get(), received.get(), ms(percentile(rttNanos, 50)));
      }
      Thread.sleep(100);
    }

    int aliveAtEnd = alive.get();
    closing = true;
    for (var ws : sockets) {
      if (!ws.isOutputClosed()) {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
      }
    }
    Thread.sleep(1000);

    System.out.println();
    System.out.println("=== summary ===");
    System.out.printf("attempted            %d%n", connections);
    System.out.printf("opened               %d%n", opened.get());
    System.out.printf("open failures        %d %s%n", failed(), openFailures.isEmpty() ? "" : openFailures);
    System.out.printf("open phase           %d ms, %.0f conn/s%n", openMillis, opened.get() * 1000.0 / Math.max(1, openMillis));
    System.out.printf("handshake latency    p50=%s p95=%s p99=%s max=%s%n",
        ms(percentile(connectNanos, 50)), ms(percentile(connectNanos, 95)), ms(percentile(connectNanos, 99)), ms(percentile(connectNanos, 100)));
    System.out.printf("alive at end of hold %d%n", aliveAtEnd);
    System.out.printf("closed by server     %d %s%n", count(serverCloses), serverCloses.isEmpty() ? "" : serverCloses);
    System.out.printf("stream errors        %d %s%n", count(errors), errors.isEmpty() ? "" : errors);
    System.out.printf("messages sent        %d%n", sent.get());
    System.out.printf("messages received    %d%n", received.get());
    if (!rttNanos.isEmpty()) {
      System.out.printf("echo rtt             p50=%s p95=%s p99=%s max=%s (%d samples)%n",
          ms(percentile(rttNanos, 50)), ms(percentile(rttNanos, 95)), ms(percentile(rttNanos, 99)), ms(percentile(rttNanos, 100)), rttNanos.size());
    }
    System.exit(0);
  }

  static class Listener implements WebSocket.Listener {
    private final StringBuilder buffer = new StringBuilder();

    @Override
    public void onOpen(WebSocket ws) {
      ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      buffer.append(data);
      if (last) {
        var message = buffer.toString();
        buffer.setLength(0);
        received.incrementAndGet();
        if (message.startsWith("ping ")) {
          try {
            rttNanos.add(System.nanoTime() - Long.parseLong(message.substring(5).trim()));
          } catch (NumberFormatException ignored) {
            // not one of ours
          }
        }
      }
      ws.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
      alive.decrementAndGet();
      if (!closing) {
        var key = statusCode + (reason == null || reason.isBlank() ? "" : " " + reason);
        serverCloses.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
      }
      return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
      alive.decrementAndGet();
      if (!closing) {
        errors.computeIfAbsent(describe(error), k -> new AtomicInteger()).incrementAndGet();
      }
    }
  }

  static int failed() {
    return count(openFailures);
  }

  static int count(Map<String, AtomicInteger> counters) {
    return counters.values().stream().mapToInt(AtomicInteger::get).sum();
  }

  static String describe(Throwable t) {
    var root = t;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    var message = root.getMessage();
    return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }

  static long percentile(List<Long> samples, int p) {
    if (samples.isEmpty()) {
      return 0;
    }
    var sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
    int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
  }

  static String ms(long nanos) {
    return String.format("%.1fms", nanos / 1_000_000.0);
  }

  static Map<String, String> parse(String[] args) {
    var opts = new java.util.HashMap<String, String>();
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith("--")) {
        var key = args[i].substring(2);
        var value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
        opts.put(key, value);
      }
    }
    return opts;
  }
}
