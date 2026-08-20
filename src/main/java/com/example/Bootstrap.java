package com.example;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import com.example.api.JwtEndpoint;
import com.example.application.PulseSecretSettings;
import com.example.application.PulseTopicSettings;
import com.example.application.SyntheticTopicConsumer;
import com.example.application.SyntheticTopicProducer;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

@Setup
public class Bootstrap implements ServiceSetup {

  private static final Logger logger = LoggerFactory.getLogger(Bootstrap.class);

  private final PulseTopicSettings topicSettings;
  private final PulseSecretSettings secretSettings;
  private final boolean topicEnabled;

  public Bootstrap(Config appConfig) {
    this.topicSettings = PulseTopicSettings.fromConfigValue(appConfig.getString("pulse.topic.publish-mode"));
    logger.info("Topic publish mode: {}", topicSettings.mode());
    this.secretSettings = PulseSecretSettings.fromConfig(
        appConfig.getString("pulse.secrets.env-prefix"),
        appConfig.getString("pulse.secrets.file-dir"));
    logger.info("Secret probe env-prefix={} file-dir={}",
        secretSettings.envPrefix(), secretSettings.fileDir());
    this.topicEnabled = appConfig.getBoolean("pulse.topic.enabled");
  }

  @Override
  public Set<Class<?>> disabledComponents() {
    var disabled = new HashSet<Class<?>>();

    var jwtIssuer = System.getenv("JWT_ISSUER");
    if (jwtIssuer != null && !jwtIssuer.isBlank()) {
      logger.info("JWT endpoint ENABLED (JWT_ISSUER={})", jwtIssuer);
    } else {
      logger.info("JWT endpoint DISABLED (set JWT_ISSUER to enable)");
      disabled.add(JwtEndpoint.class);
    }

    if (topicEnabled) {
      logger.info("Topic components ENABLED (needs a configured message broker)");
    } else {
      logger.info("Topic components DISABLED (set pulse.topic.enabled=true to enable)");
      disabled.add(SyntheticTopicProducer.class);
      disabled.add(SyntheticTopicConsumer.class);
    }

    return disabled;
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
        if (clazz == PulseSecretSettings.class) {
          return (T) secretSettings;
        }
        throw new RuntimeException("No such dependency found: " + clazz);
      }
    };
  }
}
