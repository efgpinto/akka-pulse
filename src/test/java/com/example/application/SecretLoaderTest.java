package com.example.application;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SecretLoaderTest {

  private final Config config = ConfigFactory.parseString("my.secret = from-config");

  @Test
  void readsFromFileWhenItExists(@TempDir Path dir) throws IOException {
    var file = dir.resolve("secret");
    Files.writeString(file, "from-file\n");

    assertThat(SecretLoader.load(config, file.toString(), "my.secret")).isEqualTo("from-file");
  }

  @Test
  void fallsBackToConfigWhenFileAbsent(@TempDir Path dir) {
    var file = dir.resolve("missing");

    assertThat(SecretLoader.load(config, file.toString(), "my.secret")).isEqualTo("from-config");
  }
}
