package ru.planner.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * Дополняет схему уже существующих баз: schema.sql создаёт таблицы только если их нет,
 * а новые колонки в старые таблицы SQLite приходится добавлять через ALTER TABLE.
 * Выполняется после schema.sql и до инициализации JPA.
 */
@Slf4j
@Component("schemaMigrator")
@DependsOn("dataSourceScriptDatabaseInitializer")
public class SchemaMigrator {

    public SchemaMigrator(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        addColumnIfMissing(jdbc, "tasks", "days", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing(jdbc, "tasks", "overtime", "REAL");
        addColumnIfMissing(jdbc, "tasks", "epic", "TEXT");
        addColumnIfMissing(jdbc, "tasks", "completed_early", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(jdbc, "tasks", "spent", "REAL");
        addColumnIfMissing(jdbc, "employees", "rate", "REAL NOT NULL DEFAULT 1");
    }

    private static void addColumnIfMissing(JdbcTemplate jdbc, String table, String column, String definition) {
        List<String> columns = jdbc.query("PRAGMA table_info(" + table + ")", (rs, i) -> rs.getString("name"));
        if (columns.stream().anyMatch(column::equalsIgnoreCase)) {
            return;
        }
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        log.info("Схема: добавлена колонка {}.{}", table, column);
    }

    /** JPA стартует только после миграции. */
    @Configuration
    static class JpaAfterMigration {

        @Bean
        static EntityManagerFactoryDependsOnPostProcessor entityManagerFactoryDependsOnSchemaMigrator() {
            return new EntityManagerFactoryDependsOnPostProcessor("schemaMigrator");
        }
    }
}
