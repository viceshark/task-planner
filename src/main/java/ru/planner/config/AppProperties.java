package ru.planner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Security security, Seed seed) {

    public record Security(String username, String password) {
    }

    public record Seed(boolean employees) {
    }
}
