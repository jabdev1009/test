package com.ssafy.test.global.exception;

import com.ssafy.test.global.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Custom 예외
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();

        log.warn("CustomException occurred: {} from {}, message: {}",
                errorCode.getCode(), request.getRequestURI(), errorCode.getMessage());

        ErrorResponse response = ErrorResponse.of(
                errorCode.getCode(),
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(errorCode.getStatus()).body(response);
    }

    // Validation 예외
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = Objects.requireNonNull(ex.getBindingResult().getFieldError()).getDefaultMessage();

        log.warn("MethodArgumentNotValidException occurred: {} from {}, message: {}",
                ErrorCode.INVALID_INPUT.getCode(), request.getRequestURI(), message);

        ErrorResponse response = ErrorResponse.of(
                ErrorCode.INVALID_INPUT.getCode(),
                message,
                request.getRequestURI()
        );

        return ResponseEntity.badRequest().body(response);
    }
    // 그 외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex, HttpServletRequest request) {

        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;

        log.error("Unhandled exception occurred: {} from {}", ex.getMessage(), request.getRequestURI(), ex);

        ErrorResponse response = ErrorResponse.of(
                code.getCode(),
                code.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(code.getStatus()).body(response);
    }
}
