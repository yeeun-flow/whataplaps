package com.example.assignment.user.infrastructure;

import com.example.assignment.user.application.ParseFailure;
import com.example.assignment.user.application.UserFileReadException;
import com.example.assignment.user.application.UserSnapshot;
import com.example.assignment.user.application.port.UserFileLoader;
import com.example.assignment.user.domain.InvalidUserDataException;
import com.example.assignment.user.domain.User;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TextUserFileLoader implements UserFileLoader {
  private static final Logger log = LoggerFactory.getLogger(TextUserFileLoader.class);
  private static final String BOM = "\uFEFF";
  private final Path path;
  private final UserLineParser parser = new UserLineParser();

  public TextUserFileLoader(@Value("${app.users.file:./user.txt}") String path) {
    this.path = Path.of(path);
  }

  public UserSnapshot load() {
    var usersById = new LinkedHashMap<Long, User>();
    var failures = new ArrayList<ParseFailure>();
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String rawLine;
      int lineNumber = 0;
      while ((rawLine = reader.readLine()) != null) {
        lineNumber++;
        String line = removeBomOnFirstLine(rawLine, lineNumber);
        if (line.isBlank()) {
          continue;
        }
        try {
          parseAndAddUser(line, usersById);
        } catch (InvalidUserDataException exception) {
          failures.add(new ParseFailure(lineNumber, rawLine, exception.getMessage()));
          log.warn("User parsing failed line={} reason={}", lineNumber, exception.getMessage());
        }
      }
    } catch (IOException | SecurityException exception) {
      log.error("User file loading failed", exception);
      throw new UserFileReadException(exception);
    }
    log.info("User file loaded users={} failures={}", usersById.size(), failures.size());
    return new UserSnapshot(new ArrayList<>(usersById.values()), failures);
  }

  private String removeBomOnFirstLine(String rawLine, int lineNumber) {
    return lineNumber == 1 && rawLine.startsWith(BOM) ? rawLine.substring(BOM.length()) : rawLine;
  }

  private void parseAndAddUser(String line, Map<Long, User> usersById) {
    var user = parser.parse(line);
    if (usersById.putIfAbsent(user.id(), user) != null) {
      throw new InvalidUserDataException("중복 ID입니다. 첫 번째 정상 행을 유지합니다.");
    }
  }
}
