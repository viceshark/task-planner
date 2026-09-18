package ru.planner.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.planner.domain.Task;

import java.time.LocalDate;
import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByDayBetweenOrderByDayAscPositionAscIdAsc(LocalDate from, LocalDate to);

    @Query("select coalesce(max(t.position), -1) from Task t where t.employeeId = :employeeId and t.day = :day")
    int maxPosition(@Param("employeeId") Long employeeId, @Param("day") LocalDate day);

    @Query("select distinct t.release from Task t where t.release is not null and t.release <> '' order by t.release")
    List<String> findDistinctReleases();

    List<Task> findByReleaseIsNotNull();

    @Modifying
    @Query("delete from Task t where t.employeeId = :employeeId")
    void deleteByEmployeeId(@Param("employeeId") Long employeeId);
}
