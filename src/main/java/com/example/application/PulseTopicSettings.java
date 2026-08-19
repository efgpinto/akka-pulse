package com.example.application;

/**
 * Build-time settings for the topic producer. Provided by Bootstrap from
 * {@code pulse.topic.publish-mode} in application.conf. Immutable and thread-safe.
 */
public record PulseTopicSettings(Mode mode) {

  public enum Mode {
    ORIGIN_ONLY,
    EVERY_REGION
  }

  public boolean originOnly() {
    return mode == Mode.ORIGIN_ONLY;
  }

  public static PulseTopicSettings fromConfigValue(String value) {
    if (value != null && value.trim().equalsIgnoreCase("every-region")) {
      return new PulseTopicSettings(Mode.EVERY_REGION);
    }
    return new PulseTopicSettings(Mode.ORIGIN_ONLY);
  }
}
