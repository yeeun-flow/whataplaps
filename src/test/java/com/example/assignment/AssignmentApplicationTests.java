package com.example.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.assignment.user.application.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
    properties = "app.users.file=src/test/resources/users-fixture.txt")
class AssignmentApplicationTests {
  @Autowired UserService service;

  @Test
  void startupLoadsProvidedFile() {
    assertThat(service.list(0, 100).totalElements()).isEqualTo(37);
    assertThat(service.failures()).hasSize(3);
  }
}
