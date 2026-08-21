package com.example.application;

/**
 * Settings for the secret probe endpoint. Provided by Bootstrap from
 * {@code pulse.secrets.env-prefix}, {@code pulse.secrets.file-dir}, and
 * {@code pulse.secrets.dotenv-file} in application.conf. Immutable and thread-safe.
 *
 * <p>{@code envPrefix} selects which environment variables the list probe reports.
 * {@code fileDir} is the directory where file-mounted secrets are read.
 * {@code dotenvFile} is the name of the .env-bundled secret used by the value-by-key route,
 * so callers pass only the key and the bundle is resolved by convention.
 */
public record PulseSecretSettings(String envPrefix, String fileDir, String dotenvFile) {

  public static final String DEFAULT_ENV_PREFIX = "PULSE_";
  public static final String DEFAULT_FILE_DIR = "/secrets/pulse-test-file";
  public static final String DEFAULT_DOTENV_FILE = "app-config";

  public static PulseSecretSettings fromConfig(String envPrefix, String fileDir, String dotenvFile) {
    var prefix = (envPrefix == null || envPrefix.isBlank()) ? DEFAULT_ENV_PREFIX : envPrefix.trim();
    var dir = (fileDir == null || fileDir.isBlank()) ? DEFAULT_FILE_DIR : fileDir.trim();
    var dotenv = (dotenvFile == null || dotenvFile.isBlank()) ? DEFAULT_DOTENV_FILE : dotenvFile.trim();
    return new PulseSecretSettings(prefix, dir, dotenv);
  }
}
