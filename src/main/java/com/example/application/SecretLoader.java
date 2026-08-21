package com.example.application;

import com.typesafe.config.Config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads secrets and holds the secret-related settings.
 *
 * <p>A secret is read from a mounted file if it exists, otherwise from config. Deployed, an
 * external secret (for example from AKV) is mounted at {@code <fileDir>/<name>} and wins. Locally
 * the file is absent, so the value comes from {@code pulse.file-secrets.<name>} — typically an
 * env-var substitution in application.conf. Built once from config by Bootstrap and injected.
 */
public final class SecretLoader {

  public static final String DEFAULT_ENV_PREFIX = "PULSE_";
  public static final String DEFAULT_FILE_DIR = "/secrets/pulse-test-file";
  public static final String DEFAULT_DOTENV_FILE = "app-config";

  private final Config config;
  private final String envPrefix;
  private final String fileDir;
  private final String dotenvFile;
  private final Map<String, String> dotEnv;

  public SecretLoader(Config config, String envPrefix, String fileDir, String dotenvFile) {
    this.config = config;
    this.envPrefix = orDefault(envPrefix, DEFAULT_ENV_PREFIX);
    this.fileDir = orDefault(fileDir, DEFAULT_FILE_DIR);
    this.dotenvFile = orDefault(dotenvFile, DEFAULT_DOTENV_FILE);
    // Parse the configured .env bundle once, here — this runs at bootstrap. Absent bundle
    // (e.g. local dev) yields an empty map rather than failing startup.
    this.dotEnv = exists(this.dotenvFile)
        ? Map.copyOf(parseDotEnv(load(this.dotenvFile)))
        : Map.of();
  }

  /** Builds a SecretLoader from the {@code pulse.secrets.*} config keys. */
  public static SecretLoader fromConfig(Config config) {
    return new SecretLoader(
        config,
        config.getString("pulse.secrets.env-prefix"),
        config.getString("pulse.secrets.file-dir"),
        config.getString("pulse.secrets.dotenv-file"));
  }

  public String envPrefix() {
    return envPrefix;
  }

  public String fileDir() {
    return fileDir;
  }

  public String dotenvFile() {
    return dotenvFile;
  }

  /** True when a mounted file exists for the secret. */
  public boolean isFile(String name) {
    return Files.isRegularFile(filePath(name));
  }

  /** True when the secret is available from a file or from config. */
  public boolean exists(String name) {
    return isFile(name) || config.hasPath(configPath(name));
  }

  /** Reads the secret by name: the mounted file wins, else the config value. */
  public String load(String name) {
    var path = filePath(name);
    if (Files.isRegularFile(path)) {
      try {
        return Files.readString(path).trim();
      } catch (IOException e) {
        throw new UncheckedIOException("Unable to read secret file " + path, e);
      }
    }
    return config.getString(configPath(name));
  }

  /** The configured {@code .env} bundle (pulse.secrets.dotenv-file), parsed once at construction. */
  public Map<String, String> dotEnv() {
    return dotEnv;
  }

  // Parses a .env document: KEY=VALUE lines; ignores blank lines and # comments; strips a leading
  // "export ", surrounding single/double quotes, and whitespace. Package-private for testing.
  static Map<String, String> parseDotEnv(String content) {
    var map = new LinkedHashMap<String, String>();
    if (content == null) {
      return map;
    }
    content.lines().forEach(raw -> {
      var line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        return;
      }
      if (line.startsWith("export ")) {
        line = line.substring("export ".length()).strip();
      }
      var eq = line.indexOf('=');
      if (eq <= 0) {
        return;
      }
      var key = line.substring(0, eq).strip();
      map.put(key, unquote(line.substring(eq + 1).strip()));
    });
    return map;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private Path filePath(String name) {
    return Path.of(fileDir, name);
  }

  private String configPath(String name) {
    return "pulse.file-secrets.\"" + name + "\"";
  }

  private static String orDefault(String value, String def) {
    return (value == null || value.isBlank()) ? def : value.trim();
  }
}
