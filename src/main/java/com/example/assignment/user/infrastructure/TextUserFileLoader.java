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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TextUserFileLoader implements UserFileLoader {
  private static final Logger log = LoggerFactory.getLogger(TextUserFileLoader.class);
  private final Path path;
  private final UserLineParser parser = new UserLineParser();

  public TextUserFileLoader(@Value("${app.users.file:./user.txt}") String path) {
    this.path = Path.of(path);
  }

  public UserSnapshot load() {
    var users = new LinkedHashMap<Long, User>();
    var failures = new ArrayList<ParseFailure>();
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String raw;
      int lineNumber = 0;
      boolean preamble = true;
      while ((raw = reader.readLine()) != null) {
        lineNumber++;
        String line = lineNumber == 1 && raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
        if (line.isBlank()) continue;
        if (preamble
            && (line.replaceAll("\\s", "").equals("ID,이름,나이,성별,연봉") || line.strip().matches("=+")))
          continue;
        preamble = false;
        try {
          var user = parser.parse(line);
          if (users.putIfAbsent(user.id(), user) != null)
            throw new InvalidUserDataException("중복 ID입니다. 첫 번째 정상 행을 유지합니다.");
        } catch (InvalidUserDataException exception) {
          failures.add(new ParseFailure(lineNumber, raw, exception.getMessage()));
          log.warn("User parsing failed line={} reason={}", lineNumber, exception.getMessage());
        }
      }
    } catch (IOException | SecurityException exception) {
      log.error("User file loading failed", exception);
      throw new UserFileReadException(exception);
    }
    log.info("User file loaded users={} failures={}", users.size(), failures.size());
    return new UserSnapshot(new ArrayList<>(users.values()), failures);
  }
}
