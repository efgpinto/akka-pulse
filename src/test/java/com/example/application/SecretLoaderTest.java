package com.example.application;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SecretLoaderTest {

  private final Config config = ConfigFactory.parseString(
      "pulse.file-secrets.foo = from-config\npulse.file-secrets.bar = from-config-bar");

  private SecretLoader loader(Path dir) {
    return new SecretLoader(config, "PULSE_", dir.toString(), "app-config");
  }

  @Test
  void loadReadsFileWhenPresent(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("foo"), "from-file\n");

    assertThat(loader(dir).load("foo")).isEqualTo("from-file");
  }

  @Test
  void loadFallsBackToConfigWhenNoFile(@TempDir Path dir) {
    assertThat(loader(dir).load("bar")).isEqualTo("from-config-bar");
  }

  @Test
  void existsAndIsFileReflectSources(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("foo"), "x");
    var loader = loader(dir);

    assertThat(loader.isFile("foo")).isTrue();
    assertThat(loader.isFile("bar")).isFalse();
    assertThat(loader.exists("foo")).isTrue();   // file
    assertThat(loader.exists("bar")).isTrue();   // config
    assertThat(loader.exists("nope")).isFalse();
  }

  @Test
  void dotEnvBundleIsParsedAtConstruction(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("app-config"),
        "# bundle\nDB_USER=pulse\nDB_PASSWORD=hunter2\nAPI_KEY=abc123\n");

    assertThat(loader(dir).dotEnv())
        .containsEntry("DB_USER", "pulse")
        .containsEntry("DB_PASSWORD", "hunter2")
        .containsEntry("API_KEY", "abc123");
  }

  @Test
  void dotEnvIsEmptyWhenBundleAbsent(@TempDir Path dir) {
    // No app-config file, and config has no pulse.file-secrets.app-config.
    assertThat(loader(dir).dotEnv()).isEmpty();
  }

  @Test
  void settingsUseDefaultsWhenBlank() {
    var loader = new SecretLoader(config, "", "", "");

    assertThat(loader.envPrefix()).isEqualTo(SecretLoader.DEFAULT_ENV_PREFIX);
    assertThat(loader.fileDir()).isEqualTo(SecretLoader.DEFAULT_FILE_DIR);
    assertThat(loader.dotenvFile()).isEqualTo(SecretLoader.DEFAULT_DOTENV_FILE);
  }

  @Test
  void parseDotEnvHandlesCommentsExportQuotesAndBadLines() {
    var env = SecretLoader.parseDotEnv(
        "# comment\n\nexport DB_USER = \"pulse\"\nAPI_KEY = 'abc123'\nNOT_A_PAIR\nB=2");

    assertThat(env).containsExactly(
        java.util.Map.entry("DB_USER", "pulse"),
        java.util.Map.entry("API_KEY", "abc123"),
        java.util.Map.entry("B", "2"));
  }

  @Test
  void parseDotEnvEmptyOrNullYieldsEmpty() {
    assertThat(SecretLoader.parseDotEnv("")).isEmpty();
    assertThat(SecretLoader.parseDotEnv(null)).isEmpty();
  }
}
