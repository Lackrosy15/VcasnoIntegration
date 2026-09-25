package ua.vcasno.integration;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@RestControllerAdvice
public class ErrorHandler {
    @ExceptionHandler(Failure.class)
    ResponseEntity<Map<String, String>> failure(Failure e) {
        return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "Проверьте leadId и JSON с полем fopId"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e) {
        // Do not leak request headers, credentials, provider bodies or SQL parameters.
        org.slf4j.LoggerFactory.getLogger(ErrorHandler.class).error("Internal failure: {}", e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("code", "INTERNAL_ERROR",
                "message", "Внутренняя ошибка. Повторите исходный запрос; не создавайте новую операцию вручную"));
    }
}
