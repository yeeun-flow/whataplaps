package com.example.assignment.user.domain;

public record User(long id, String name, int age, String gender, long salary) {
  public User {
    if (id <= 0) throw new InvalidUserDataException("ID는 양수여야 합니다.");
    if (name == null || name.isBlank()) throw new InvalidUserDataException("이름이 비어 있습니다.");
    if (age < 0) throw new InvalidUserDataException("나이는 음수일 수 없습니다.");
    if (gender == null || gender.isBlank()) throw new InvalidUserDataException("성별이 비어 있습니다.");
    if (salary < 0) throw new InvalidUserDataException("연봉은 음수일 수 없습니다.");
  }
}
