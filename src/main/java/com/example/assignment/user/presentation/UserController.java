package com.example.assignment.user.presentation;

import com.example.assignment.user.application.UserService;
import com.example.assignment.user.domain.User;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {
  private final UserService service;

  public UserController(UserService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<UserPageResponse> list(
      @RequestParam(defaultValue = "0") @Min(value = 0, message = "page는 0 이상이어야 합니다.") int page,
      @RequestParam(defaultValue = "20")
          @Min(value = 1, message = "size는 1 이상이어야 합니다.")
          @Max(value = 100, message = "size는 100 이하여야 합니다.")
          int size) {
    var result = service.list(page, size);
    return ResponseEntity.ok(
        new UserPageResponse(
            result.content().stream().map(UserResponse::from).toList(),
            result.page(),
            result.size(),
            result.totalElements(),
            result.totalPages()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<UserResponse> find(
      @PathVariable @Positive(message = "id는 양수여야 합니다.") long id) {
    return ResponseEntity.ok(UserResponse.from(service.find(id)));
  }

  @GetMapping("/search")
  public ResponseEntity<List<UserResponse>> search(
      @RequestParam @NotBlank(message = "name 검색어는 공백일 수 없습니다.") String name) {
    return ResponseEntity.ok(service.search(name).stream().map(UserResponse::from).toList());
  }

  @GetMapping("/parse-errors")
  public ResponseEntity<List<ParseFailureResponse>> failures() {
    return ResponseEntity.ok(
        service.failures().stream()
            .map(f -> new ParseFailureResponse(f.lineNumber(), f.rawLine(), f.reason()))
            .toList());
  }

  @PostMapping("/reload")
  public ResponseEntity<ReloadResponse> reload() {
    var result = service.reload();
    return ResponseEntity.ok(new ReloadResponse(result.loadedCount(), result.failedCount()));
  }

  public record UserResponse(long id, String name, int age, String gender, long salary) {
    static UserResponse from(User user) {
      return new UserResponse(user.id(), user.name(), user.age(), user.gender(), user.salary());
    }
  }

  public record UserPageResponse(
      List<UserResponse> content, int page, int size, long totalElements, int totalPages) {}

  public record ParseFailureResponse(int lineNumber, String rawLine, String reason) {}

  public record ReloadResponse(int loadedCount, int failedCount) {}
}
