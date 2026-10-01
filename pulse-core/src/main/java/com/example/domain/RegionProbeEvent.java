package com.example.domain;

import akka.javasdk.annotations.TypeName;
import java.time.Instant;

/** Events of the multi-region probe entities. Region and time are captured in the command handler. */
public sealed interface RegionProbeEvent {

  @TypeName("probe-written")
  record ProbeWritten(String value, int seq, boolean withTimer, String writtenIn, Instant writtenAt)
      implements RegionProbeEvent {}
}
