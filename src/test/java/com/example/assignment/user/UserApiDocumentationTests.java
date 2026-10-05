package com.example.assignment.user;

import static com.epages.restdocs.apispec.ResourceDocumentation.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.*;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.epages.restdocs.apispec.ResourceSnippetParameters;
import com.epages.restdocs.apispec.Schema;
import com.epages.restdocs.apispec.SimpleType;
import com.example.assignment.user.application.UserService;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ExtendWith(RestDocumentationExtension.class)
class UserApiDocumentationTests {
  static final Path FILE = createFile();
  @Autowired WebApplicationContext context;
  @Autowired UserService service;
  MockMvc mvc;

  static Path createFile() {
    try {
      var file = Files.createTempFile("user-docs-", ".txt");
      Files.writeString(file, "1,김다나,23,M,30_000_000\n");
      return file;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.users.file", FILE::toString);
  }

  @AfterAll
  static void cleanup() throws Exception {
    Files.deleteIfExists(FILE);
  }

  @BeforeEach
  void setup(RestDocumentationContextProvider docs) throws Exception {
    Files.writeString(
        FILE, "1,김다나,23,M,30_000_000\n2,이다다,28,F,35_000_000\n3,바사삭,38,F,45_OOO_000\n");
    service.reload();
    mvc =
        MockMvcBuilders.webAppContextSetup(context).apply(documentationConfiguration(docs)).build();
  }

  FieldDescriptor[] userFields(String prefix) {
    return new FieldDescriptor[] {
      fieldWithPath(prefix + "id").type(JsonFieldType.NUMBER).description("양수 ID (64비트 정수)"),
      fieldWithPath(prefix + "name").type(JsonFieldType.STRING).description("이름"),
      fieldWithPath(prefix + "age").type(JsonFieldType.NUMBER).description("0 이상의 나이 (32비트 정수)"),
      fieldWithPath(prefix + "gender").type(JsonFieldType.STRING).description("성별 문자열"),
      fieldWithPath(prefix + "salary")
          .type(JsonFieldType.NUMBER)
          .description("0 이상의 연봉 (64비트 정수, 밑줄 없는 숫자)")
    };
  }

  FieldDescriptor[] errorFields() {
    return new FieldDescriptor[] {
      fieldWithPath("code").type(JsonFieldType.STRING).description("오류 코드"),
      fieldWithPath("message").type(JsonFieldType.STRING).description("공개 오류 메시지")
    };
  }

  @Test
  void list() throws Exception {
    var fields =
        new java.util.ArrayList<FieldDescriptor>(java.util.List.of(userFields("content[].")));
    fields.add(fieldWithPath("content").type(JsonFieldType.ARRAY).description("파일 순서의 사용자 목록"));
    fields.add(fieldWithPath("page").type(JsonFieldType.NUMBER).description("0부터 시작하는 페이지"));
    fields.add(fieldWithPath("size").type(JsonFieldType.NUMBER).description("페이지 크기 (1~100)"));
    fields.add(
        fieldWithPath("totalElements").type(JsonFieldType.NUMBER).description("전체 정상 사용자 수"));
    fields.add(fieldWithPath("totalPages").type(JsonFieldType.NUMBER).description("전체 페이지 수"));
    mvc.perform(get("/users").param("page", "0").param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value(1))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andDo(
            document(
                "users-page",
                responseFields(fields),
                resource(
                    ResourceSnippetParameters.builder()
                        .tag("Users")
                        .description("전체 사용자 페이지 조회; 범위 밖 페이지는 빈 목록")
                        .queryParameters(
                            parameterWithName("page")
                                .type(SimpleType.INTEGER)
                                .description("0 이상, 기본 0")
                                .optional(),
                            parameterWithName("size")
                                .type(SimpleType.INTEGER)
                                .description("1~100, 기본 20")
                                .optional())
                        .responseSchema(Schema.schema("UserPage"))
                        .responseFields(fields)
                        .build())));
  }

  @Test
  void find() throws Exception {
    mvc.perform(get("/users/{id}", 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("김다나"))
        .andExpect(jsonPath("$.salary").value(30000000))
        .andDo(
            document(
                "user-detail",
                responseFields(userFields("")),
                resource(
                    ResourceSnippetParameters.builder()
                        .tag("Users")
                        .description("ID로 사용자 조회")
                        .pathParameters(
                            parameterWithName("id")
                                .type(SimpleType.INTEGER)
                                .description("양수 사용자 ID"))
                        .responseSchema(Schema.schema("User"))
                        .responseFields(userFields(""))
                        .build())));
  }

  @Test
  void search() throws Exception {
    mvc.perform(get("/users/search").param("name", "다나"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("김다나"))
        .andDo(
            document(
                "users-search",
                responseFields(userFields("[].")),
                resource(
                    ResourceSnippetParameters.builder()
                        .tag("Users")
                        .description("이름 부분 문자열 검색; 앞뒤 공백 제거, 대소문자 구분, 결과 없으면 빈 배열")
                        .queryParameters(parameterWithName("name").description("공백이 아닌 검색어"))
                        .responseSchema(Schema.schema("UserList"))
                        .responseFields(userFields("[]."))
                        .build())));
  }

  @Test
  void failures() throws Exception {
    var fields =
        new FieldDescriptor[] {
          fieldWithPath("[].lineNumber")
              .type(JsonFieldType.NUMBER)
              .description("파일의 1부터 시작하는 실제 행 번호"),
          fieldWithPath("[].rawLine").type(JsonFieldType.STRING).description("파싱 실패 원문"),
          fieldWithPath("[].reason").type(JsonFieldType.STRING).description("실패 사유")
        };
    mvc.perform(get("/users/parse-errors"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].lineNumber").value(3))
        .andExpect(jsonPath("$[0].rawLine").value("3,바사삭,38,F,45_OOO_000"))
        .andDo(
            document(
                "users-parse-errors",
                responseFields(fields),
                resource(
                    ResourceSnippetParameters.builder()
                        .tag("Users")
                        .description("마지막으로 적재된 파일의 파싱 실패 목록")
                        .responseSchema(Schema.schema("ParseFailures"))
                        .responseFields(fields)
                        .build())));
  }

  @Test
  void reload() throws Exception {
    Files.writeString(FILE, "9,신규,30,F,9000\n");
    var fields =
        new FieldDescriptor[] {
          fieldWithPath("loadedCount").type(JsonFieldType.NUMBER).description("이번 정상 적재 수"),
          fieldWithPath("failedCount").type(JsonFieldType.NUMBER).description("이번 파싱 실패 수")
        };
    mvc.perform(post("/users/reload"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.loadedCount").value(1))
        .andExpect(jsonPath("$.failedCount").value(0))
        .andDo(
            document(
                "users-reload",
                responseFields(fields),
                resource(
                    ResourceSnippetParameters.builder()
                        .tag("Users")
                        .description("설정된 파일을 다시 읽어 정상 데이터와 실패 목록을 일괄 교체; 요청 본문 없음")
                        .responseSchema(Schema.schema("ReloadResult"))
                        .responseFields(fields)
                        .build())));
    mvc.perform(get("/users/{id}", 9)).andExpect(status().isOk());
    mvc.perform(get("/users/{id}", 1)).andExpect(status().isNotFound());
  }

  @Test
  void notFound() throws Exception {
    mvc.perform(get("/users/{id}", 999))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
        .andDo(
            document(
                "user-not-found",
                responseFields(errorFields()),
                resource(
                    ResourceSnippetParameters.builder()
                        .tag("Users")
                        .pathParameters(
                            parameterWithName("id")
                                .type(SimpleType.INTEGER)
                                .description("양수 사용자 ID"))
                        .responseSchema(Schema.schema("ApiError"))
                        .responseFields(errorFields())
                        .build())));
  }

  @Test
  void invalidPage() throws Exception {
    mvc.perform(get("/users").param("size", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andDo(
            document(
                "users-invalid-page",
                responseFields(errorFields()),
                resource(
                    ResourceSnippetParameters.builder()
                        .tag("Users")
                        .queryParameters(
                            parameterWithName("page")
                                .type(SimpleType.INTEGER)
                                .description("0 이상, 기본 0")
                                .optional(),
                            parameterWithName("size")
                                .type(SimpleType.INTEGER)
                                .description("1~100, 기본 20")
                                .optional())
                        .responseSchema(Schema.schema("ApiError"))
                        .responseFields(errorFields())
                        .build())));
  }

  @Test
  void invalidSearch() throws Exception {
    mvc.perform(get("/users/search").param("name", " "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andDo(
            document(
                "users-invalid-search",
                responseFields(errorFields()),
                resource(
                    ResourceSnippetParameters.builder()
                        .tag("Users")
                        .queryParameters(parameterWithName("name").description("공백이 아닌 검색어"))
                        .responseSchema(Schema.schema("ApiError"))
                        .responseFields(errorFields())
                        .build())));
  }

  @Test
  void unavailableFile() throws Exception {
    Files.delete(FILE);
    mvc.perform(post("/users/reload"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("USER_FILE_UNAVAILABLE"))
        .andDo(
            document(
                "users-file-unavailable",
                responseFields(errorFields()),
                resource(
                    ResourceSnippetParameters.builder()
                        .tag("Users")
                        .responseSchema(Schema.schema("ApiError"))
                        .responseFields(errorFields())
                        .build())));
    mvc.perform(get("/users/{id}", 1)).andExpect(status().isOk());
  }
}
