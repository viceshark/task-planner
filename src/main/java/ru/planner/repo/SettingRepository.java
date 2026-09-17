package ru.planner.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.planner.domain.AppSetting;

public interface SettingRepository extends JpaRepository<AppSetting, String> {
}
