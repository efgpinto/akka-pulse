package com.example.application;

/**
 * Settings for the secret probe endpoint. Provided by Bootstrap from
 * {@code pulse.secrets.env-prefix} and {@code pulse.secrets.file-dir} in
 * application.conf. Immutable and thread-safe.
 *
 * <p>{@code envPrefix} selects which environment variables the list probe reports.
 * {@code fileDir} is the directory where file-mounted secrets are read.
 */
public record PulseSecretSettings(String envPrefix, String fileDir) {

  public static final String DEFAULT_ENV_PREFIX = "PULSE_";
  public static final String DEFAULT_FILE_DIR = "/secrets/pulse-test-file";

  public static PulseSecretSettings fromConfig(String envPrefix, String fileDir) {
    var prefix = (envPrefix == null || envPrefix.isBlank()) ? DEFAULT_ENV_PREFIX : envPrefix.trim();
    var dir = (fileDir == null || fileDir.isBlank()) ? DEFAULT_FILE_DIR : fileDir.trim();
    return new PulseSecretSettings(prefix, dir);
  }
}
