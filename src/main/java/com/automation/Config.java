package com.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class Config {
    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static final Properties properties = new Properties();

    static {
        File configFile = new File("config.properties");
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                properties.load(fis);
                logger.info("Loaded configuration from {}", configFile.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to load config.properties", e);
            }
        } else {
            logger.warn("config.properties not found. Using default settings.");
        }
    }

    public static int getThreads() {
        return Integer.parseInt(properties.getProperty("threads", "2"));
    }

    public static int getTimeoutSeconds() {
        return Integer.parseInt(properties.getProperty("timeout.seconds", "60"));
    }

    public static int getRetries() {
        return Integer.parseInt(properties.getProperty("retries", "2"));
    }

    public static String getIgnoreSelectors() {
        return properties.getProperty("ignore.selectors", "");
    }

    public static boolean isCacheEnabled() {
        return Boolean.parseBoolean(properties.getProperty("cache.enabled", "true"));
    }

    public static int getCacheTtlHours() {
        return Integer.parseInt(properties.getProperty("cache.ttl.hours", "24"));
    }
}
