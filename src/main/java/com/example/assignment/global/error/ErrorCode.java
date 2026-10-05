package com.example.assignment.global.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum ErrorCode {
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 내용을 확인해 주세요."),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 자원을 찾을 수 없습니다."),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
  NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "요청한 응답 형식을 지원하지 않습니다."),
  PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "요청 크기가 허용 범위를 초과했습니다."),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
  SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 요청을 처리할 수 없습니다."),
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
  USER_FILE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "사용자 파일을 읽을 수 없습니다. 기존 데이터를 유지합니다."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getMessage() {
    return message;
  }

  public static ErrorCode fromStatus(HttpStatusCode status) {
    for (ErrorCode errorCode : values()) {
      if (errorCode.status.value() == status.value()) {
        return errorCode;
      }
    }
    return status.is4xxClientError() ? INVALID_REQUEST : INTERNAL_ERROR;
  }
}
