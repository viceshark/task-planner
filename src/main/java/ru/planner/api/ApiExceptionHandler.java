package ru.planner.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ru.planner.service.NotFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Единый JSON-формат ошибок для REST API: {"message": "...", "errors": {"поле": "сообщение"}}. */
@Slf4j
@RestControllerAdvice(basePackages = "ru.planner.api")
public class ApiExceptionHandler {

    public record ApiError(String message, Map<String, String> errors) {
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        String message = errors.isEmpty() ? "Некорректный запрос" : String.join("; ", errors.values());
        return ResponseEntity.badRequest().body(new ApiError(message, errors));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> badRequest(Exception ex) {
        String message = ex instanceof IllegalArgumentException ? ex.getMessage() : "Некорректный запрос";
        return ResponseEntity.badRequest().body(new ApiError(message, Map.of()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex) {
        log.error("Необработанная ошибка API", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ApiError("Внутренняя ошибка сервера", Map.of()));
    }
}
