package dev.seatlock;

import java.util.Map;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

public class ApiError extends RuntimeException {
  final int status;
  final String code;

  ApiError(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

@RestControllerAdvice
class Errors {
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> known(ApiError e) {
    return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_INPUT", "message", "Check your input and try again."));
  }

  @ExceptionHandler(ConcurrencyFailureException.class)
  ResponseEntity<?> busy(Exception e) {
    return ResponseEntity.status(409)
        .body(Map.of("code", "RETRY", "message", "Those seats are busy. Refresh and try again."));
  }
}
