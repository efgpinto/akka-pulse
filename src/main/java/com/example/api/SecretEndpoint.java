package com.example.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.HttpResponses;
import com.example.application.DotEnv;
import com.example.application.PulseSecretSettings;
import com.example.application.SecretLoader;
import com.typesafe.config.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Synthetic probe that verifies external secrets reach the running service.
 * It reports secrets injected as environment variables and as volume-mounted files.
 *
 * <p>This is a test tool. It operates on test secrets only. It returns the full value
 * so an engineer can confirm the exact injected content, and it is open to the internet
 * like the other Pulse probes. Do not point it at real production secrets.
 */
@HttpEndpoint("/pulse/secrets")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class SecretEndpoint {

  // Presence status of a probed secret.
  public static final String PRESENT = "PRESENT";
  public static final String EMPTY = "EMPTY";
  public static final String ERROR = "ERROR";

  public record SecretResponse(
      String name, String value, String source, String status, Instant readAt) {}

  public record SecretEntry(String name, String value, String source, String status) {}

  public record AllSecretsResponse(
      List<SecretEntry> envSecrets, List<SecretEntry> fileSecrets, Instant readAt) {}

  public record LoadedSecretResponse(
      String name, String value, String source, Instant readAt) {}

  public record DotEnvResponse(
      String name, Map<String, String> entries, int count, Instant readAt) {}

  private final PulseSecretSettings settings;
  private final Config config;

  public SecretEndpoint(PulseSecretSettings settings, Config config) {
    this.settings = settings;
    this.config = config;
  }

  // Read a secret from a named environment variable.
  @Get("/env/{envVarName}")
  public HttpResponse getEnvSecret(String envVarName) {
    return Optional.ofNullable(envVarName).filter(n -> !n.isBlank())
        .map(System::getenv)
        .map(value -> HttpResponses.ok(
            new SecretResponse(envVarName, value, "env", statusOf(value), Instant.now())))
        .orElseGet(() ->
            HttpResponses.notFound("Environment variable '" + envVarName + "' not set"));
  }

  // Read a secret from a file in the configured secrets directory.
  @Get("/file/{fileName}")
  public HttpResponse getFileSecret(String fileName) {
    var path = Path.of(settings.fileDir(), fileName);
    if (!Files.exists(path)) {
      return HttpResponses.notFound("Secret file not found at " + path);
    }
    try {
      var value = Files.readString(path).trim();
      return HttpResponses.ok(
          new SecretResponse(fileName, value, "file:" + path, statusOf(value), Instant.now()));
    } catch (IOException e) {
      return HttpResponses.badRequest("Error reading secret file: " + e.getMessage());
    }
  }

  // List all Pulse test secrets from both sources.
  @Get("/")
  public AllSecretsResponse getAllPulseSecrets() {
    return new AllSecretsResponse(envSecrets(), fileSecrets(), Instant.now());
  }

  // Load a secret with SecretLoader: the mounted file wins, else the config value. The file
  // lives at <file-dir>/<name>; the config fallback is pulse.file-secrets.<name>.
  @Get("/load/{name}")
  public HttpResponse loadSecret(String name) {
    var filePath = settings.fileDir() + "/" + name;
    var configPath = "pulse.file-secrets.\"" + name + "\"";
    var fromFile = Files.isRegularFile(Path.of(filePath));
    if (!fromFile && !config.hasPath(configPath)) {
      return HttpResponses.notFound(
          "No secret file at " + filePath + " and no config at pulse.file-secrets." + name);
    }
    var value = SecretLoader.load(config, filePath, configPath);
    return HttpResponses.ok(
        new LoadedSecretResponse(name, value, fromFile ? "file" : "config", Instant.now()));
  }

  // Load a secret that bundles multiple values as a .env document (KEY=VALUE lines) and return
  // the parsed entries. Demonstrates one secret object holding many values (file wins, else config).
  @Get("/dotenv/{name}")
  public HttpResponse getDotEnvSecret(String name) {
    var filePath = settings.fileDir() + "/" + name;
    var configPath = "pulse.file-secrets.\"" + name + "\"";
    if (!Files.isRegularFile(Path.of(filePath)) && !config.hasPath(configPath)) {
      return HttpResponses.notFound(
          "No secret file at " + filePath + " and no config at pulse.file-secrets." + name);
    }
    var entries = DotEnv.parse(SecretLoader.load(config, filePath, configPath));
    return HttpResponses.ok(new DotEnvResponse(name, entries, entries.size(), Instant.now()));
  }

  private List<SecretEntry> envSecrets() {
    var entries = new ArrayList<SecretEntry>();
    System.getenv().entrySet().stream()
        .filter(e -> e.getKey().startsWith(settings.envPrefix()))
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(e ->
            entries.add(new SecretEntry(e.getKey(), e.getValue(), "env", statusOf(e.getValue()))));
    return entries;
  }

  private List<SecretEntry> fileSecrets() {
    var entries = new ArrayList<SecretEntry>();
    var dir = Path.of(settings.fileDir());
    if (!Files.isDirectory(dir)) {
      return entries;
    }
    try (var stream = Files.list(dir).sorted()) {
      stream.forEach(p -> entries.add(readFileEntry(p)));
    } catch (IOException e) {
      // Directory became unreadable; report nothing rather than failing the probe.
    }
    return entries;
  }

  private SecretEntry readFileEntry(Path path) {
    var name = path.getFileName().toString();
    try {
      var value = Files.readString(path).trim();
      return new SecretEntry(name, value, "file", statusOf(value));
    } catch (IOException e) {
      return new SecretEntry(name, "", "file", ERROR);
    }
  }

  private static String statusOf(String value) {
    return value.isEmpty() ? EMPTY : PRESENT;
  }
}
