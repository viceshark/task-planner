package ru.planner.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.planner.api.dto.AbsenceDto;
import ru.planner.api.dto.AbsenceRequest;
import ru.planner.service.AbsenceService;

import java.time.LocalDate;
import java.util.List;

/** Простои, аренды и отпуска. */
@RestController
@RequestMapping("/api/absences")
@RequiredArgsConstructor
public class AbsenceController {

    private final AbsenceService absences;

    @GetMapping
    public List<AbsenceDto> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return absences.list(from, to);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AbsenceDto create(@Valid @RequestBody AbsenceRequest request) {
        return absences.create(request);
    }

    @PutMapping("/{id}")
    public AbsenceDto update(@PathVariable Long id, @Valid @RequestBody AbsenceRequest request) {
        return absences.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        absences.delete(id);
    }
}
