package com.example.assignment.global.error;

import com.example.assignment.user.application.InvalidUserQueryException;
import com.example.assignment.user.application.UserFileReadException;
import com.example.assignment.user.application.UserNotFoundException;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  @Override
  protected @Nullable ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ErrorCode code = ErrorCode.fromStatus(status);
    String message = code.getMessage();
    if (status.value() == 400) {
      if (exception instanceof HandlerMethodValidationException validation) {
        message =
            validation.getAllErrors().stream()
                .map(
                    error ->
                        error.getDefaultMessage() == null
                            ? code.getMessage()
                            : error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining(" "));
      } else if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
        message = mismatch.getName() + "은(는) 올바른 정수 형식과 범위로 입력해 주세요.";
      } else if (exception instanceof MissingServletRequestParameterException missing) {
        message = missing.getParameterName() + "은(는) 필수 입력값입니다.";
      }
    }
    if (status.value() == 500) logger.error("Unexpected request processing failure", exception);
    var responseHeaders = new HttpHeaders();
    responseHeaders.putAll(headers);
    responseHeaders.setContentType(MediaType.APPLICATION_JSON);
    return super.handleExceptionInternal(
        exception, new ApiErrorResponse(code.name(), message), responseHeaders, status, request);
  }

  @ExceptionHandler(InvalidUserQueryException.class)
  public ResponseEntity<ApiErrorResponse> invalidQuery() {
    return ResponseEntity.badRequest()
        .body(
            new ApiErrorResponse(
                ErrorCode.INVALID_REQUEST.name(),
                "page는 0 이상, size는 1~100이어야 하며 검색어는 공백일 수 없습니다."));
  }

  @ExceptionHandler(UserNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> userNotFound() {
    return error(ErrorCode.USER_NOT_FOUND);
  }

  @ExceptionHandler(UserFileReadException.class)
  public ResponseEntity<ApiErrorResponse> fileUnavailable() {
    return error(ErrorCode.USER_FILE_UNAVAILABLE);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> unexpected(Exception exception) {
    logger.error("Unexpected request processing failure", exception);
    return error(ErrorCode.INTERNAL_ERROR);
  }

  private ResponseEntity<ApiErrorResponse> error(ErrorCode code) {
    return ResponseEntity.status(code.getStatus()).body(ApiErrorResponse.of(code));
  }
}
