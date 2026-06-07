package com.example.domain;

import java.time.Instant;

public record HealthCheckEntry(Instant timestamp, String status) {
}
