package com.axion.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * Entry point for the axion-auth module.
 * Handles Instagram Business OAuth 2.0 via Meta Graph API.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.axion.auth.config")
@EnableScheduling
public class AxionAuthApplication {

    public static void main(String[] args) {
        // PostgreSQL JDBC does not accept some legacy JVM timezone ids such as Asia/Calcutta.
        // Normalizing the app process to UTC keeps local boot stable across environments.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(AxionAuthApplication.class, args);
    }
}
