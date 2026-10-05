package com.example.assignment.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.assignment.user.application.UserService;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserHttpTests {
  static final Path FILE = createFile();
  @LocalServerPort int port;
  @Autowired UserService service;
  final ObjectMapper mapper = new ObjectMapper();

  static Path createFile() {
    try {
      var file = Files.createTempFile("user-http-", ".txt");
      Files.writeString(file, "1,김가,20,M,1_000\n");
      return file;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void fileProperty(DynamicPropertyRegistry properties) {
    properties.add("app.users.file", FILE::toString);
  }

  @AfterAll
  static void cleanup() throws Exception {
    Files.deleteIfExists(FILE);
  }

  @BeforeEach
  void reset() throws Exception {
    Files.writeString(FILE, "1,김가,20,M,1_000\n2,김나,21,F,2000\n3,이다,22,M,3000\nbad\n");
    service.reload();
  }

  HttpResponse<String> request(String path, boolean post) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
      if (post) request.POST(HttpRequest.BodyPublishers.noBody());
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  @Test
  void readsPageUserSearchAndFailuresOverHttp() throws Exception {
    var response = request("/users?page=1&size=2", false);
    assertThat(response.statusCode()).isEqualTo(200);
    var page = mapper.readTree(response.body());
    assertThat(page.get("totalElements").asInt()).isEqualTo(3);
    assertThat(page.get("totalPages").asInt()).isEqualTo(2);
    assertThat(page.get("content").size()).isEqualTo(1);
    assertThat(page.get("content").get(0).get("id").asInt()).isEqualTo(3);
    var detail = request("/users/1", false);
    assertThat(detail.statusCode()).isEqualTo(200);
    var user = mapper.readTree(detail.body());
    assertThat(user.size()).isEqualTo(5);
    assertThat(user.get("id").asLong()).isEqualTo(1);
    assertThat(user.get("name").asString()).isEqualTo("김가");
    assertThat(user.get("age").asInt()).isEqualTo(20);
    assertThat(user.get("gender").asString()).isEqualTo("M");
    assertThat(user.get("salary").asLong()).isEqualTo(1000);
    var errors = request("/users/parse-errors", false);
    assertThat(errors.statusCode()).isEqualTo(200);
    var failureRow = mapper.readTree(errors.body()).get(0);
    assertThat(failureRow.get("rawLine").asString()).isEqualTo("bad");
    assertThat(failureRow.get("reason").asString()).contains("5개");
    assertThat(mapper.readTree(request("/users/search?name=%EA%B9%80", false).body()).size())
        .isEqualTo(2);
    assertThat(
            mapper
                .readTree(request("/users/parse-errors", false).body())
                .get(0)
                .get("lineNumber")
                .asInt())
        .isEqualTo(4);
    assertThat(
            mapper
                .readTree(request("/users?page=2147483647&size=100", false).body())
                .get("content")
                .size())
        .isZero();
  }

  @Test
  void rejectsInvalidInputsWithSafeError() throws Exception {
    for (var path :
        new String[] {
          "/users?page=-1",
          "/users?size=0",
          "/users?size=-1",
          "/users?size=101",
          "/users?size=2147483647",
          "/users?page=abc",
          "/users/0",
          "/users/abc",
          "/users/search",
          "/users/search?name=%20"
        }) {
      var response = request(path, false);
      assertThat(response.statusCode()).as(path).isEqualTo(400);
      var error = mapper.readTree(response.body());
      assertThat(error.get("code").asString()).isEqualTo("INVALID_REQUEST");
      assertThat(error.get("message").asString()).isNotBlank();
      assertThat(error.size()).isEqualTo(2);
    }
    var missing = request("/users/999", false);
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(missing.body()).get("code").asString()).isEqualTo("USER_NOT_FOUND");
  }

  @Test
  void defaultsAndSizeBoundaryEmptyResultsAndWrongReloadMethod() throws Exception {
    var defaults = mapper.readTree(request("/users", false).body());
    assertThat(defaults.get("page").asInt()).isZero();
    assertThat(defaults.get("size").asInt()).isEqualTo(20);
    for (String size : new String[] {"1", "100"}) {
      var response = request("/users?size=" + size, false);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(mapper.readTree(response.body()).get("content").size())
          .isEqualTo(Math.min(Integer.parseInt(size), 3));
    }
    assertThat(mapper.readTree(request("/users/search?name=unknown", false).body()).size())
        .isZero();
    assertThat(mapper.readTree(request("/users/search?name=%20%EA%B9%80%20", false).body()).size())
        .isEqualTo(2);
    assertThat(request("/users/search?name=%00", false).statusCode()).isEqualTo(400);
    Files.writeString(FILE, "9,새이름,30,F,9000\n");
    // GET matches /users/{id}; "reload" is not a numeric ID. It must not trigger a reload.
    assertThat(request("/users/reload", false).statusCode()).isEqualTo(400);
    assertThat(request("/users/1", false).statusCode()).isEqualTo(200);
    assertThat(request("/users/9", false).statusCode()).isEqualTo(404);
  }

  @Test
  void allInvalidAndEmptyReloadsSucceedOverHttp() throws Exception {
    Files.writeString(FILE, "bad\n");
    var invalid = request("/users/reload", true);
    assertThat(invalid.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(invalid.body()).get("loadedCount").asInt()).isZero();
    assertThat(mapper.readTree(invalid.body()).get("failedCount").asInt()).isEqualTo(1);
    assertThat(request("/users/1", false).statusCode()).isEqualTo(404);
    Files.writeString(FILE, "");
    var empty = request("/users/reload", true);
    assertThat(empty.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(empty.body()).get("failedCount").asInt()).isZero();
    assertThat(mapper.readTree(request("/users/parse-errors", false).body()).size()).isZero();
  }

  @Test
  void directoryReadFailureKeepsDataAndDoesNotExposeInternalCause() throws Exception {
    Files.delete(FILE);
    Files.createDirectory(FILE);
    try {
      var response = request("/users/reload", true);
      assertThat(response.statusCode()).isEqualTo(503);
      var error = mapper.readTree(response.body());
      assertThat(error.size()).isEqualTo(2);
      assertThat(error.get("code").asString()).isEqualTo("USER_FILE_UNAVAILABLE");
      assertThat(error.get("message").asString()).isEqualTo("사용자 파일을 읽을 수 없습니다. 기존 데이터를 유지합니다.");
      assertThat(response.body())
          .doesNotContain(FILE.toString(), "IOException", "Is a directory", "stackTrace");
      assertThat(request("/users/1", false).statusCode()).isEqualTo(200);
      assertThat(
              mapper
                  .readTree(request("/users/parse-errors", false).body())
                  .get(0)
                  .get("rawLine")
                  .asString())
          .isEqualTo("bad");
    } finally {
      Files.delete(FILE);
    }
  }

  @Test
  void reloadUpdatesDataAndFailurePreservesIt() throws Exception {
    Files.writeString(FILE, "9,새이름,30,F,9000\n");
    var reloaded = request("/users/reload", true);
    assertThat(reloaded.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(reloaded.body()).get("loadedCount").asInt()).isEqualTo(1);
    assertThat(mapper.readTree(reloaded.body()).get("failedCount").asInt()).isZero();
    assertThat(request("/users/1", false).statusCode()).isEqualTo(404);
    assertThat(request("/users/9", false).statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(request("/users/parse-errors", false).body()).size()).isZero();
    Files.delete(FILE);
    var failure = request("/users/reload", true);
    assertThat(failure.statusCode()).isEqualTo(503);
    assertThat(failure.body())
        .contains("USER_FILE_UNAVAILABLE")
        .doesNotContain(FILE.toString(), "NoSuchFileException");
    assertThat(request("/users/9", false).statusCode()).isEqualTo(200);
  }
}
