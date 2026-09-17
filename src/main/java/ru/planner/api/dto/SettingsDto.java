package ru.planner.api.dto;

import jakarta.validation.constraints.Size;

public record SettingsDto(@Size(max = 500, message = "URL не длиннее 500 символов") String jiraBaseUrl) {
}
