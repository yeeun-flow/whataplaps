package com.example.assignment.user.application.port;

import com.example.assignment.user.application.UserSnapshot;

public interface UserFileLoader {
  UserSnapshot load();
}
