package com.example.assignment.user.infrastructure;

import com.example.assignment.user.application.UserService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class UserStartupLoader implements ApplicationRunner {
  private final UserService service;

  public UserStartupLoader(UserService service) {
    this.service = service;
  }

  public void run(ApplicationArguments args) {
    service.reload();
  }
}
