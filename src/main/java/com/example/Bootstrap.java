package com.example;

import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import com.example.api.JwtEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

@Setup
public class Bootstrap implements ServiceSetup {

  private static final Logger logger = LoggerFactory.getLogger(Bootstrap.class);

  @Override
  public Set<Class<?>> disabledComponents() {
    var jwtIssuer = System.getenv("JWT_ISSUER");
    if (jwtIssuer != null && !jwtIssuer.isBlank()) {
      logger.info("JWT endpoint ENABLED (JWT_ISSUER={})", jwtIssuer);
      return Set.of();
    } else {
      logger.info("JWT endpoint DISABLED (set JWT_ISSUER to enable)");
      return Set.of(JwtEndpoint.class);
    }
  }
}
