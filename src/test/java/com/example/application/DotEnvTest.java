package com.example.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DotEnvTest {

  @Test
  void parsesKeyValueLines() {
    var env = DotEnv.parse("DB_USER=pulse\nDB_PASSWORD=hunter2\nAPI_KEY=abc123");

    assertThat(env).containsEntry("DB_USER", "pulse")
        .containsEntry("DB_PASSWORD", "hunter2")
        .containsEntry("API_KEY", "abc123");
  }

  @Test
  void ignoresBlankLinesAndComments() {
    var env = DotEnv.parse("# a comment\n\nDB_USER=pulse\n   # indented comment\n");

    assertThat(env).hasSize(1).containsEntry("DB_USER", "pulse");
  }

  @Test
  void stripsExportPrefixAndQuotesAndWhitespace() {
    var env = DotEnv.parse("export DB_USER = \"pulse\"\nAPI_KEY = 'abc123'");

    assertThat(env).containsEntry("DB_USER", "pulse").containsEntry("API_KEY", "abc123");
  }

  @Test
  void skipsLinesWithoutEquals() {
    var env = DotEnv.parse("NOT_A_PAIR\nDB_USER=pulse");

    assertThat(env).hasSize(1).containsEntry("DB_USER", "pulse");
  }

  @Test
  void emptyOrNullInputYieldsEmptyMap() {
    assertThat(DotEnv.parse("")).isEmpty();
    assertThat(DotEnv.parse(null)).isEmpty();
  }

  @Test
  void preservesInsertionOrder() {
    var env = DotEnv.parse("A=1\nB=2\nC=3");

    assertThat(env.keySet()).containsExactly("A", "B", "C");
    assertThat(env).isInstanceOf(Map.class);
  }
}
