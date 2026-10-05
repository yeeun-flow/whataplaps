package com.example.assignment.user.application;

public class UserFileReadException extends RuntimeException {
  public UserFileReadException(Throwable cause) {
    super("사용자 파일을 읽을 수 없습니다.", cause);
  }
}
