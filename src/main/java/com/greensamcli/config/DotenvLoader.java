package com.greensamcli.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 最小化的 .env 文件加载器。
 * 从 .env 文件中读取 KEY=VALUE 键值对。
 * 以 # 开头的行视为注释，空行会被跳过。
 * 值可以不带引号、带单引号或带双引号。
 */
public final class DotenvLoader {

    private DotenvLoader() {
    }

    /**
     * 从指定目录的 .env 文件中加载环境变量。
     * 文件不存在时返回空 map。
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
