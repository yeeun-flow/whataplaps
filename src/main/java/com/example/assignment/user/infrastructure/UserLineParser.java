package com.example.assignment.user.infrastructure;

import com.example.assignment.user.domain.InvalidUserDataException;
import com.example.assignment.user.domain.User;

public class UserLineParser {
  public User parse(String line) {
    var fields = line.split(",", -1);
    if (fields.length != 5) throw new InvalidUserDataException("필드는 ID, 이름, 나이, 성별, 연봉 5개여야 합니다.");
    for (int i = 0; i < fields.length; i++) fields[i] = fields[i].strip();
    long id = number(fields[0], "ID");
    long age = number(fields[2], "나이");
    if (age > Integer.MAX_VALUE) throw new InvalidUserDataException("나이의 정수 범위를 초과했습니다.");
    if (!fields[4].matches("[0-9]+|[0-9]{1,3}(?:_[0-9]{3})+"))
      throw new InvalidUserDataException("연봉의 정수 형식이 올바르지 않습니다.");
    long salary = number(fields[4].replace("_", ""), "연봉");
    return new User(id, fields[1], (int) age, fields[3], salary);
  }

  private long number(String value, String field) {
    if (!value.matches("[0-9]+")) throw new InvalidUserDataException(field + "의 정수 형식이 올바르지 않습니다.");
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new InvalidUserDataException(field + "의 정수 범위를 초과했습니다.");
    }
  }
}
