package com.example.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.timedaction.TimedAction;

/** Records in which region a probe timer fired (spec 002, US8, checklist R4). */
@Component(id = "region-probe-timed-action")
public class RegionProbeTimedAction extends TimedAction {

  private final ComponentClient componentClient;

  public RegionProbeTimedAction(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public Effect fire(String kind) {
    var region = commandContext().selfRegion();
    componentClient.forKeyValueEntity(kind + "-" + region)
        .method(ProbeLedgerEntity::record)
        .invoke(new ProbeLedgerEntity.RecordCommand("", false, region));
    return effects().done();
  }
}
