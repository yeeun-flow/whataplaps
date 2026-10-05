package com.example.assignment.user.application;

public record ParseFailure(int lineNumber, String rawLine, String reason) {}
