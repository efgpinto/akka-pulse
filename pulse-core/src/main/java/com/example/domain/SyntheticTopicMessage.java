package com.example.domain;

import akka.javasdk.annotations.TypeName;

@TypeName("synthetic-topic-message")
public record SyntheticTopicMessage(
    String recordId,
    String eventType,
    String value,
    String originRegion) {
}
