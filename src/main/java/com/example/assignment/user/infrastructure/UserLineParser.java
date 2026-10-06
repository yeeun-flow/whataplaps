package com.example.assignment.user.infrastructure;

import com.example.assignment.user.domain.InvalidUserDataException;
import com.example.assignment.user.domain.User;
import java.util.regex.Pattern;

public class UserLineParser {
  private static final int FIELD_COUNT = 5;
  private static final int ID_INDEX = 0;
  private static final int NAME_INDEX = 1;
  private static final int AGE_INDEX = 2;
  private static final int GENDER_INDEX = 3;
  private static final int SALARY_INDEX = 4;
  private static final Pattern INTEGER_PATTERN = Pattern.compile("[0-9]+");
  private static final Pattern SALARY_PATTERN = Pattern.compile("[0-9]+|[0-9]{1,3}(?:_[0-9]{3})+");

  public User parse(String line) {
    String[] fields = splitAndTrim(line);
    long id = parseNumber(fields[ID_INDEX], "ID");
    String name = fields[NAME_INDEX];
    int age = parseAge(fields[AGE_INDEX]);
    String gender = fields[GENDER_INDEX];
    long salary = parseSalary(fields[SALARY_INDEX]);
    return new User(id, name, age, gender, salary);
  }

  private String[] splitAndTrim(String line) {
    String[] fields = line.split(",", -1);
    if (fields.length != FIELD_COUNT) {
      throw new InvalidUserDataException("필드는 ID, 이름, 나이, 성별, 연봉 5개여야 합니다.");
    }
    for (int i = 0; i < fields.length; i++) {
      fields[i] = fields[i].strip();
    }
    return fields;
  }

  private int parseAge(String value) {
    long age = parseNumber(value, "나이");
    if (age > Integer.MAX_VALUE) throw new InvalidUserDataException("나이의 정수 범위를 초과했습니다.");
    return (int) age;
  }

  private long parseSalary(String value) {
    if (!SALARY_PATTERN.matcher(value).matches())
      throw new InvalidUserDataException("연봉의 정수 형식이 올바르지 않습니다.");
    return parseNumber(value.replace("_", ""), "연봉");
  }

  private long parseNumber(String value, String field) {
    if (!INTEGER_PATTERN.matcher(value).matches())
      throw new InvalidUserDataException(field + "의 정수 형식이 올바르지 않습니다.");
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new InvalidUserDataException(field + "의 정수 범위를 초과했습니다.");
    }
  }
}
