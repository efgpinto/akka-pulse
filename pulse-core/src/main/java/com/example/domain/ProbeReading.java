package com.example.domain;

/**
 * A probe entity reply: the state, and the region where the command handler ran.
 *
 * <p>{@code callMicros} is set by the endpoint: the time the component call took, measured inside
 * the service. It shows the cost of a primary switch or a forward without the client round-trip.
 */
public record ProbeReading(RegionProbeState state, String handledIn, Long callMicros) {

  public ProbeReading(RegionProbeState state, String handledIn) {
    this(state, handledIn, null);
  }

  public ProbeReading withCallMicros(long micros) {
    return new ProbeReading(state, handledIn, micros);
  }
}
