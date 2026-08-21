package com.example.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses a {@code .env}-style string (KEY=VALUE lines) into a map. Used to read a single secret
 * object that bundles multiple values — e.g. one Azure Key Vault secret whose value is a small
 * {@code .env} document mounted as a file.
 *
 * <p>Rules: blank lines and {@code #} comments are ignored, a leading {@code export } is stripped,
 * surrounding single or double quotes are removed, and keys/values are trimmed. Lines without an
 * {@code =} are skipped.
 */
public final class DotEnv {

  private DotEnv() {}

  public static Map<String, String> parse(String content) {
    var map = new LinkedHashMap<String, String>();
    if (content == null) {
      return map;
    }
    content.lines().forEach(raw -> {
      var line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        return;
      }
      if (line.startsWith("export ")) {
        line = line.substring("export ".length()).strip();
      }
      var eq = line.indexOf('=');
      if (eq <= 0) {
        return;
      }
      var key = line.substring(0, eq).strip();
      var value = unquote(line.substring(eq + 1).strip());
      map.put(key, value);
    });
    return map;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
