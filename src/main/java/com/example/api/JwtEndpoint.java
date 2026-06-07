package com.example.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.JWT;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.AbstractHttpEndpoint;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@HttpEndpoint("/pulse/jwt")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN)
public class JwtEndpoint extends AbstractHttpEndpoint {

  public record JwtTestResponse(
      String status,
      Instant timestamp,
      String issuer,
      String subject,
      Map<String, String> claims) {}

  @Get("/test")
  public JwtTestResponse testJwt() {
    var jwtClaims = requestContext().getJwtClaims();
    var issuer = jwtClaims.issuer().orElse("unknown");
    var subject = jwtClaims.subject().orElse("unknown");
    var allClaims = new HashMap<String, String>();
    for (String name : jwtClaims.allClaimNames()) {
      allClaims.put(name, jwtClaims.getString(name).orElse(""));
    }

    return new JwtTestResponse("AUTHENTICATED", Instant.now(), issuer, subject, allClaims);
  }
}
