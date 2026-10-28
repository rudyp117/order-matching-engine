package dev.tape.api;

import dev.tape.domain.ExchangeException;
import dev.tape.persistence.PostgresJournal.JournalFailure;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public final class ApiErrors {
    public record ErrorBody(String code, String message) { }
    @ExceptionHandler(ExchangeException.class)
    public ResponseEntity<ErrorBody> exchange(ExchangeException failure) {
        int status = switch (failure.code()) {
            case INVALID_INPUT -> 400;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case UNAVAILABLE -> 503;
        };
        return ResponseEntity.status(status).body(new ErrorBody(failure.code().name(), failure.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_INPUT",
                "Invalid JSON or parameter. Use BUY/SELL, integer priceTicks 1..100000000 and quantity 1..1000000; depth 1..100, limit 1..200."));
    }
    @ExceptionHandler(JournalFailure.class)
    public ResponseEntity<ErrorBody> unavailable(JournalFailure ignored) {
        return ResponseEntity.status(503).body(new ErrorBody("UNAVAILABLE", "Database unavailable"));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> missing(NoResourceFoundException ignored) {
        return ResponseEntity.status(404).body(new ErrorBody("NOT_FOUND", "Resource not found"));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorBody> method(HttpRequestMethodNotSupportedException ignored) {
        return ResponseEntity.status(405).body(new ErrorBody("METHOD_NOT_ALLOWED", "HTTP method not supported"));
    }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorBody> media(HttpMediaTypeNotSupportedException ignored) {
        return ResponseEntity.status(415).body(new ErrorBody("UNSUPPORTED_MEDIA_TYPE", "Use application/json"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception failure) {
        System.getLogger(ApiErrors.class.getName()).log(System.Logger.Level.ERROR, "Request failed", failure);
        return ResponseEntity.status(500).body(new ErrorBody("INTERNAL_ERROR", "Unexpected server error"));
    }
}
