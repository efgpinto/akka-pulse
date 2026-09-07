package com.example.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.example.application.SyntheticWorkflow;
import com.example.domain.SyntheticWorkflowState;

@HttpEndpoint("/pulse/workflows")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class WorkflowEndpoint {

  private final ComponentClient componentClient;

  public WorkflowEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/{workflowId}/start")
  public HttpResponse startWorkflow(String workflowId, SyntheticWorkflow.StartWorkflowRequest request) {
    componentClient.forWorkflow(workflowId)
        .method(SyntheticWorkflow::start)
        .invoke(request);
    return HttpResponses.created(workflowId);
  }

  @Get("/{workflowId}")
  public SyntheticWorkflowState getWorkflowStatus(String workflowId) {
    return componentClient.forWorkflow(workflowId)
        .method(SyntheticWorkflow::getStatus)
        .invoke();
  }
}
