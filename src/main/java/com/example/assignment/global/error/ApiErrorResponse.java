package com.example.assignment.global.error;

public record ApiErrorResponse(String code, String message) {
  public static ApiErrorResponse of(ErrorCode code) {
    return new ApiErrorResponse(code.name(), code.getMessage());
  }
}
