package com.greensamcli.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal .env file loader.
 * Reads KEY=VALUE pairs from a .env file.
 * Lines starting with # are comments. Blank lines are skipped.
 * Values may be unquoted, single-quoted, or double-quoted.
 */
public final class DotenvLoader {

    private DotenvLoader() {}

    /**
     * Loads env vars from the .env file in the given directory.
     * Returns an empty map if the file does not exist.
     */
    public static Map<String, String> load(Path directory) {
        Path envFile = directory.resolve(".env");
        if (!Files.exists(envFile)) {
            return Map.of();
        }
        try {
            Map<String, String> result = new HashMap<>();
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
            return Collections.unmodifiableMap(result);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
