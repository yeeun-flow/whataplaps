package com.example.assignment.user.domain;

public class InvalidUserDataException extends RuntimeException {
  public InvalidUserDataException(String message) {
    super(message);
  }
}
