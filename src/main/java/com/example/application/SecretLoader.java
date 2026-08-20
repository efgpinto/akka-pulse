package com.example.application;

import com.typesafe.config.Config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a secret from a file if it exists, otherwise from config.
 *
 * <p>Deployed, an external secret (for example from AKV) is mounted at {@code filePath} and
 * wins. Locally, the file is absent, so the value comes from {@code configPath} — typically an
 * env-var substitution in application.conf. The file path is given inline at the call site.
 */
public final class SecretLoader {

  private SecretLoader() {}

  public static String load(Config config, String filePath, String configPath) {
    var path = Path.of(filePath);
    if (Files.isRegularFile(path)) {
      try {
        return Files.readString(path).trim();
      } catch (IOException e) {
        throw new UncheckedIOException("Unable to read secret file " + path, e);
      }
    }
    return config.getString(configPath);
  }
}
