package com.example.assignment.user.application;

import com.example.assignment.user.domain.User;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record UserSnapshot(List<User> users, List<ParseFailure> failures, Map<Long, User> byId) {
  public UserSnapshot(List<User> users, List<ParseFailure> failures) {
    this(
        List.copyOf(users),
        List.copyOf(failures),
        users.stream().collect(Collectors.toUnmodifiableMap(User::id, Function.identity())));
  }

  public UserSnapshot {
    users = List.copyOf(users);
    failures = List.copyOf(failures);
    byId = Map.copyOf(byId);
  }
}
