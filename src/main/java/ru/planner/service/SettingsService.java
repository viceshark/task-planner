package ru.planner.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.planner.api.dto.SettingsDto;
import ru.planner.domain.AppSetting;
import ru.planner.repo.SettingRepository;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SettingRepository settings;

    @Transactional(readOnly = true)
    public SettingsDto get() {
        String jira = settings.findById(AppSetting.JIRA_BASE_URL).map(AppSetting::getValue).orElse("");
        return new SettingsDto(jira);
    }

    @Transactional
    public SettingsDto update(SettingsDto dto) {
        String jira = dto.jiraBaseUrl() == null ? "" : dto.jiraBaseUrl().trim().replaceAll("/+$", "");
        settings.save(new AppSetting(AppSetting.JIRA_BASE_URL, jira));
        return new SettingsDto(jira);
    }
}
