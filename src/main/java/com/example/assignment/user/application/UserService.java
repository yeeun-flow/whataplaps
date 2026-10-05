package com.example.assignment.user.application;

import com.example.assignment.user.application.port.UserFileLoader;
import com.example.assignment.user.domain.User;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class UserService {
  private final UserFileLoader loader;
  private volatile UserSnapshot snapshot = new UserSnapshot(List.of(), List.of());

  public UserService(UserFileLoader loader) {
    this.loader = loader;
  }

  public Page list(int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw new InvalidUserQueryException();
    var users = snapshot.users();
    long offset = (long) page * size;
    var content =
        offset >= users.size()
            ? List.<User>of()
            : users.subList((int) offset, (int) Math.min(offset + size, users.size()));
    return new Page(
        content, page, size, users.size(), (int) ((users.size() + (long) size - 1) / size));
  }

  public User find(long id) {
    var user = snapshot.byId().get(id);
    if (user == null) throw new UserNotFoundException();
    return user;
  }

  public List<User> search(String name) {
    if (name == null || name.trim().isEmpty()) throw new InvalidUserQueryException();
    String query = name.trim();
    return snapshot.users().stream().filter(user -> user.name().contains(query)).toList();
  }

  public List<ParseFailure> failures() {
    return snapshot.failures();
  }

  // Serialize reloads; readers always see one complete immutable snapshot.
  public synchronized ReloadResult reload() {
    var next = loader.load();
    snapshot = next;
    return new ReloadResult(next.users().size(), next.failures().size());
  }

  public record Page(List<User> content, int page, int size, long totalElements, int totalPages) {}

  public record ReloadResult(int loadedCount, int failedCount) {}
}
