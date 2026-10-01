package com.example.application;

import akka.javasdk.testkit.EventSourcedTestKit;
import com.example.domain.ProbeReading;
import com.example.domain.RegionProbeEvent.ProbeWritten;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RegionProbeEntityTest {

  @Test
  public void writeCapturesRegionAndTimeInTheEvent() {
    var testKit = EventSourcedTestKit.of("p-1", RegionProbeEntity::new);

    var result = testKit.method(RegionProbeEntity::write)
        .invoke(new RegionProbeEntity.WriteCommand("a", true));

    var event = (ProbeWritten) result.getAllEvents().getFirst();
    assertThat(event.seq()).isEqualTo(1);
    assertThat(event.withTimer()).isTrue();
    assertThat(event.writtenAt()).isNotNull();
    assertThat(event.writtenIn()).isNotNull();

    var reply = result.getReply();
    assertThat(reply.state().writtenAt()).isEqualTo(event.writtenAt());
    assertThat(reply.state().appliedAt()).isNotNull();
  }

  @Test
  public void seqIncreasesAndReadsReturnTheSameState() {
    var testKit = EventSourcedTestKit.of("p-1", RegionProbeEntity::new);
    testKit.method(RegionProbeEntity::write).invoke(new RegionProbeEntity.WriteCommand("a", false));
    testKit.method(RegionProbeEntity::write).invoke(new RegionProbeEntity.WriteCommand("b", false));

    ProbeReading plain = testKit.method(RegionProbeEntity::readPlain).invoke().getReply();
    ProbeReading readOnly = testKit.method(RegionProbeEntity::readOnly).invoke().getReply();

    assertThat(plain.state().seq()).isEqualTo(2);
    assertThat(plain.state().value()).isEqualTo("b");
    assertThat(readOnly.state()).isEqualTo(plain.state());
  }

  @Test
  public void readOfEmptyEntityReturnsNoState() {
    var testKit = EventSourcedTestKit.of("p-1", RegionProbeEntity::new);
    assertThat(testKit.method(RegionProbeEntity::readOnly).invoke().getReply().state()).isNull();
  }
}
