package com.example.common.domain;

import java.time.Instant;

public record HealthCheckEntry(Instant timestamp, String status, String region) {
}
