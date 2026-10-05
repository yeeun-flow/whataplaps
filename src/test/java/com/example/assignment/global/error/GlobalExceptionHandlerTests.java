package com.example.assignment.global.error;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.assignment.user.application.UserService;
import com.example.assignment.user.presentation.UserController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
class GlobalExceptionHandlerTests {
  @Autowired MockMvc mvc;
  @MockitoBean UserService service;

  @ParameterizedTest
  @CsvSource({
    "/users?page=-1,page는 0 이상",
    "/users?size=0,size는 1 이상",
    "/users?size=101,size는 100 이하",
    "/users?page=abc,page은(는) 올바른 정수",
    "/users/0,id는 양수",
    "/users/search,name은(는) 필수 입력값"
  })
  void validationMessagesExplainTheInvalidInput(String path, String message) throws Exception {
    mvc.perform(get(path))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.message", containsString(message)))
        .andExpect(jsonPath("$.*", hasSize(2)));
    verifyNoInteractions(service);
  }

  @Test
  void blankSearchIsRejectedBeforeCallingService() throws Exception {
    mvc.perform(get("/users/search").param("name", " "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message", containsString("검색어는 공백일 수 없습니다")));
    verifyNoInteractions(service);
  }

  @Test
  void unexpectedFailureDoesNotExposeInternalDetails() throws Exception {
    when(service.find(1)).thenThrow(new IllegalStateException("secret path /private/user.txt"));
    mvc.perform(get("/users/1"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
        .andExpect(content().string(not(containsString("secret"))));
  }

  @Test
  void frameworkErrorsPreserveStatusAndAllowHeader() throws Exception {
    mvc.perform(post("/users/1"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", containsString("GET")))
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    mvc.perform(get("/unknown-path"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    verifyNoInteractions(service);
  }
}
