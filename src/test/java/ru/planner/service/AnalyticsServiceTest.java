package ru.planner.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsServiceTest {

    @Test
    void normalizeTitleExtractsJiraKeyOrFirstLine() {
        assertThat(AnalyticsService.normalizeTitle("MOSSPOR-2198")).isEqualTo("MOSSPOR-2198");
        assertThat(AnalyticsService.normalizeTitle("  фикс MOSSPOR-2198 и ещё\nвторая строка")).isEqualTo("MOSSPOR-2198");
        assertThat(AnalyticsService.normalizeTitle("ПРОЕКТ-42 кириллица")).isEqualTo("ПРОЕКТ-42");
        assertThat(AnalyticsService.normalizeTitle("баги и код ревью\nMOSSPOR-1")).isEqualTo("баги и код ревью");
        assertThat(AnalyticsService.normalizeTitle("   \n  ")).isEqualTo("(без названия)");
    }
}
