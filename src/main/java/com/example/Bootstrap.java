package com.example;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import com.example.api.JwtEndpoint;
import com.example.application.PulseTopicSettings;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

@Setup
public class Bootstrap implements ServiceSetup {

  private static final Logger logger = LoggerFactory.getLogger(Bootstrap.class);

  private final PulseTopicSettings topicSettings;

  public Bootstrap(Config appConfig) {
    this.topicSettings = PulseTopicSettings.fromConfigValue(appConfig.getString("pulse.topic.publish-mode"));
    logger.info("Topic publish mode: {}", topicSettings.mode());
  }

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

  @Override
  public DependencyProvider createDependencyProvider() {
    return new DependencyProvider() {
      @SuppressWarnings("unchecked")
      @Override
      public <T> T getDependency(Class<T> clazz) {
        if (clazz == PulseTopicSettings.class) {
          return (T) topicSettings;
        }
        throw new RuntimeException("No such dependency found: " + clazz);
      }
    };
  }
}
