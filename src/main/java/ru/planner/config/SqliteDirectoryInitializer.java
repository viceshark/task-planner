package ru.planner.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SQLite создаёт файл базы сам, но не создаёт каталог для него.
 * Перед стартом контекста убеждаемся, что каталог из spring.datasource.url существует.
 */
public class SqliteDirectoryInitializer implements EnvironmentPostProcessor {

    private static final String PREFIX = "jdbc:sqlite:";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith(PREFIX)) {
            return;
        }
        String file = url.substring(PREFIX.length());
        int query = file.indexOf('?');
        if (query >= 0) {
            file = file.substring(0, query);
        }
        if (file.isBlank() || file.startsWith(":memory:") || file.startsWith("file:")) {
            return;
        }
        Path parent = Path.of(file).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать каталог для базы данных: " + parent, e);
        }
    }
}
