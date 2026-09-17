package ru.planner.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.planner.api.dto.SettingsDto;
import ru.planner.service.SettingsService;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settings;

    @GetMapping
    public SettingsDto get() {
        return settings.get();
    }

    @PutMapping
    public SettingsDto update(@Valid @RequestBody SettingsDto dto) {
        return settings.update(dto);
    }
}
