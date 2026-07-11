package com.example.essentialrag.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ErrorResponse handleBadRequest(IllegalArgumentException exception) {
    return new ErrorResponse(
        Instant.now().toString(),
        HttpStatus.BAD_REQUEST.value(),
        exception.getMessage());
  }

  public record ErrorResponse(String timestamp, int status, String message) {
  }
}
