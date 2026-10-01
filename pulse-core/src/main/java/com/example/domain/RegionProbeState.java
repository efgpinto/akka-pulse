package com.example.domain;

import java.time.Instant;

/**
 * State of a multi-region probe entity.
 *
 * <p>{@code writtenIn} and {@code writtenAt} come from the event and converge in every region.
 * {@code appliedIn} and {@code appliedAt} are computed when the event is applied, so they are
 * region-local on purpose. Key Value Entity probes leave them empty.
 */
public record RegionProbeState(
    String probeId,
    String value,
    int seq,
    String writtenIn,
    Instant writtenAt,
    String appliedIn,
    Instant appliedAt) {}
