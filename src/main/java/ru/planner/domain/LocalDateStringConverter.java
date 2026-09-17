package ru.planner.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDate;

/**
 * SQLite не имеет типа DATE, поэтому даты храним в ISO-формате (yyyy-MM-dd):
 * такие строки корректно сортируются и сравниваются в запросах.
 */
@Converter
public class LocalDateStringConverter implements AttributeConverter<LocalDate, String> {

    @Override
    public String convertToDatabaseColumn(LocalDate attribute) {
        return attribute == null ? null : attribute.toString();
    }

    @Override
    public LocalDate convertToEntityAttribute(String dbData) {
        return dbData == null || dbData.isBlank() ? null : LocalDate.parse(dbData);
    }
}
