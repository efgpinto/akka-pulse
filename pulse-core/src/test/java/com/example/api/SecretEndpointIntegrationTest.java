package com.example.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class SecretEndpointIntegrationTest extends TestKitSupport {

  // Temp secrets directory created before the kit starts, referenced from test config.
  private static final Path SECRETS_DIR = createSecretsDir();

  private static Path createSecretsDir() {
    try {
      var dir = Files.createTempDirectory("pulse-secret-test");
      Files.writeString(dir.resolve("api-token"), "s3cr3t-value\n");
      Files.writeString(dir.resolve("empty-secret"), "");
      Files.writeString(dir.resolve("app-config"),
          "# app config bundle\nDB_USER=pulse\nDB_PASSWORD=hunter2\nAPI_KEY=abc123\n");
      return dir;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withDisabledComponents(Set.of(JwtEndpoint.class))
        .withAdditionalConfig("pulse.secrets.file-dir = \"" + SECRETS_DIR + "\"\n"
            + "pulse.file-secrets.demo-key = from-config");
  }

  @Test
  public void envSecretPresentReturnsFullValue() {
    // Probe a real environment variable present in the test JVM.
    var existing = anyEnvVar();
    var response = httpClient.GET("/pulse/secrets/env/" + existing.getKey())
        .responseBodyAs(SecretEndpoint.SecretResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().name()).isEqualTo(existing.getKey());
    assertThat(response.body().value()).isEqualTo(existing.getValue());
    assertThat(response.body().source()).isEqualTo("env");
    assertThat(response.body().status()).isEqualTo(SecretEndpoint.PRESENT);
  }

  @Test
  public void envSecretMissingReturnsNotFound() {
    var response = httpClient.GET("/pulse/secrets/env/PULSE_DEFINITELY_MISSING_XYZ")
        .invoke();

    assertThat(response.status().intValue()).isEqualTo(404);
  }

  @Test
  public void fileSecretPresentReturnsFullValue() {
    var response = httpClient.GET("/pulse/secrets/file/api-token")
        .responseBodyAs(SecretEndpoint.SecretResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().value()).isEqualTo("s3cr3t-value");
    assertThat(response.body().status()).isEqualTo(SecretEndpoint.PRESENT);
    assertThat(response.body().source()).startsWith("file:");
  }

  @Test
  public void fileSecretEmptyReportsEmptyStatus() {
    var response = httpClient.GET("/pulse/secrets/file/empty-secret")
        .responseBodyAs(SecretEndpoint.SecretResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().value()).isEmpty();
    assertThat(response.body().status()).isEqualTo(SecretEndpoint.EMPTY);
  }

  @Test
  public void fileSecretMissingReturnsNotFound() {
    var response = httpClient.GET("/pulse/secrets/file/does-not-exist")
        .invoke();

    assertThat(response.status().intValue()).isEqualTo(404);
  }

  @Test
  public void listReportsFileSecrets() {
    var response = httpClient.GET("/pulse/secrets/")
        .responseBodyAs(SecretEndpoint.AllSecretsResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    var fileNames = response.body().fileSecrets().stream()
        .map(SecretEndpoint.SecretEntry::name)
        .toList();
    assertThat(fileNames).contains("api-token", "empty-secret");
    assertThat(response.body().fileSecrets())
        .anySatisfy(e -> {
          assertThat(e.name()).isEqualTo("empty-secret");
          assertThat(e.status()).isEqualTo(SecretEndpoint.EMPTY);
        });
  }

  @Test
  public void loadReadsFromFileWhenPresent() {
    // 'api-token' exists as a mounted file, so the file value wins.
    var response = httpClient.GET("/pulse/secrets/load/api-token")
        .responseBodyAs(SecretEndpoint.LoadedSecretResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().value()).isEqualTo("s3cr3t-value");
    assertThat(response.body().source()).isEqualTo("file");
  }

  @Test
  public void loadFallsBackToConfigWhenNoFile() {
    // 'demo-key' has no file, so the value comes from pulse.file-secrets.demo-key.
    var response = httpClient.GET("/pulse/secrets/load/demo-key")
        .responseBodyAs(SecretEndpoint.LoadedSecretResponse.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body().value()).isEqualTo("from-config");
    assertThat(response.body().source()).isEqualTo("config");
  }

  @Test
  public void loadMissingSecretReturnsNotFound() {
    var response = httpClient.GET("/pulse/secrets/load/nope")
        .invoke();

    assertThat(response.status().intValue()).isEqualTo(404);
  }

  @Test
  public void valueByKeyUsesConfiguredBundle() {
    // No bundle name in the URL — resolved from pulse.secrets.dotenv-file (default app-config),
    // parsed at bootstrap.
    var response = httpClient.GET("/pulse/secrets/value/API_KEY")
        .responseBodyAs(String.class)
        .invoke();

    assertThat(response.status().isSuccess()).isTrue();
    assertThat(response.body()).isEqualTo("abc123");
  }

  @Test
  public void valueMissingKeyReturnsNotFound() {
    var response = httpClient.GET("/pulse/secrets/value/NOPE")
        .invoke();

    assertThat(response.status().intValue()).isEqualTo(404);
  }

  private static Map.Entry<String, String> anyEnvVar() {
    return System.getenv().entrySet().stream()
        .filter(e -> !e.getValue().isEmpty())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No environment variables available"));
  }
}
