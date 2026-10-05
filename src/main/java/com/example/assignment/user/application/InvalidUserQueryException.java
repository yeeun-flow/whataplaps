package com.example.assignment.user.application;

public class InvalidUserQueryException extends RuntimeException {
  public InvalidUserQueryException() {
    super("Invalid user query");
  }
}
