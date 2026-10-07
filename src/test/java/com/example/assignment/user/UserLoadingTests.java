package com.example.assignment.user;

import static org.assertj.core.api.Assertions.*;

import com.example.assignment.user.application.*;
import com.example.assignment.user.domain.*;
import com.example.assignment.user.infrastructure.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserLoadingTests {
  @TempDir Path directory;

  @Test
  void parsesProvidedFileWithoutCorrectingInvalidSalary() {
    var result = new TextUserFileLoader("src/test/resources/users-fixture.txt").load();
    assertThat(result.users()).hasSize(37);
    assertThat(result.byId().get(1001L)).isEqualTo(new User(1001, "김다나", 23, "M", 30_000_000));
    assertThat(result.failures()).extracting(ParseFailure::lineNumber).containsExactly(16, 31, 39);
    assertThat(result.failures())
        .extracting(ParseFailure::rawLine)
        .containsExactly(
            "1016 , 바사삭 , 38 , F , 45_OOO_000",
            "1031 , 일본 , 53 , F ,, 60_000_000",
            "1039 , 농심 , 61 , M , 68_AAA_000");
    assertThat(result.byId()).containsKeys(1017L, 1032L, 1040L);
  }

  @Test
  void preservesLineNumbersAndFirstValidDuplicate() throws Exception {
    var file = directory.resolve("users.txt");
    Files.writeString(file, "\uFEFF1, 첫째,0,F,0\n\n1,둘째,20,M,100\n2,셋째,21,M,1_000\n");
    var result = new TextUserFileLoader(file.toString()).load();
    assertThat(result.users()).extracting(User::name).containsExactly("첫째", "셋째");
    assertThat(result.failures())
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.lineNumber()).isEqualTo(3);
              assertThat(f.reason()).contains("중복");
            });
  }

  @Test
  void headerAndSeparatorAreFailuresRegardlessOfPosition() throws Exception {
    var file = directory.resolve("users.txt");
    Files.writeString(
        file, "\n ID , 이름 , 나이 , 성별 , 연봉\n===\nbad\nID,이름,나이,성별,연봉\n===\n1,정상,20,X,100\n");

    var result = new TextUserFileLoader(file.toString()).load();

    assertThat(result.users()).containsExactly(new User(1, "정상", 20, "X", 100));
    assertThat(result.failures())
        .extracting(ParseFailure::lineNumber)
        .containsExactly(2, 3, 4, 5, 6);
    assertThat(result.failures())
        .extracting(ParseFailure::rawLine)
        .containsExactly(" ID , 이름 , 나이 , 성별 , 연봉", "===", "bad", "ID,이름,나이,성별,연봉", "===");
  }

  @Test
  void removesBomOnlyOnFirstLineAndPreservesFailureOriginal() throws Exception {
    var file = directory.resolve("users.txt");
    Files.writeString(file, "\uFEFF1,오류,20,X,wrong\n\uFEFF2,이름,20,X,100\n3,정상,20,X,100\n");

    var result = new TextUserFileLoader(file.toString()).load();

    assertThat(result.users()).containsExactly(new User(3, "정상", 20, "X", 100));
    assertThat(result.failures())
        .containsExactly(
            new ParseFailure(1, "\uFEFF1,오류,20,X,wrong", "연봉의 정수 형식이 올바르지 않습니다."),
            new ParseFailure(2, "\uFEFF2,이름,20,X,100", "ID의 정수 형식이 올바르지 않습니다."));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1,이름,20,M",
        "1,이름,20,M,100,extra",
        "1,,20,M,100",
        "1,이름,,M,100",
        "1,이름,20,,100",
        "0,이름,20,M,100",
        "-1,이름,20,M,100",
        "1,이름,-1,M,100",
        "1,이름,20,M,-100",
        "1,이름,20,M,45_OOO_000",
        "1,이름,20,M,1__000",
        "1,이름,20,M,1_00",
        "1,이름,20,M,1.5",
        "1,이름,2147483648,M,100",
        "9223372036854775808,이름,20,M,100",
        "1,이름,20,M,9223372036854775808"
      })
  void rejectsMalformedRows(String line) {
    assertThatThrownBy(() -> new UserLineParser().parse(line))
        .isInstanceOf(InvalidUserDataException.class);
  }

  @Test
  void validatesDomainWithoutSpring() {
    assertThatThrownBy(() -> new User(1, " ", 20, "M", 0))
        .isInstanceOf(InvalidUserDataException.class);
    assertThatThrownBy(() -> new User(1, "이름", -1, "M", 0))
        .isInstanceOf(InvalidUserDataException.class);
    assertThatThrownBy(() -> new User(1, "이름", 20, "M", -1))
        .isInstanceOf(InvalidUserDataException.class);
  }

  @Test
  void reloadReplacesBothListsAndReadFailurePreservesPreviousSnapshot() throws Exception {
    var file = directory.resolve("users.txt");
    Files.writeString(file, "1,첫째,20,M,100\nbad\n");
    var service = new UserService(new TextUserFileLoader(file.toString()));
    assertThat(service.reload()).isEqualTo(new UserService.ReloadResult(1, 1));
    Files.writeString(file, "2,둘째,21,F,200\n");
    assertThat(service.reload()).isEqualTo(new UserService.ReloadResult(1, 0));
    assertThatThrownBy(() -> service.find(1)).isInstanceOf(UserNotFoundException.class);
    Files.delete(file);
    assertThatThrownBy(service::reload).isInstanceOf(UserFileReadException.class);
    assertThat(service.find(2).name()).isEqualTo("둘째");
    assertThat(service.failures()).isEmpty();
    Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
    assertThatThrownBy(service::reload).isInstanceOf(UserFileReadException.class);
    assertThat(service.find(2).name()).isEqualTo("둘째");
    Files.writeString(file, "");
    assertThat(service.reload()).isEqualTo(new UserService.ReloadResult(0, 0));
    assertThat(service.list(0, 20).content()).isEmpty();
  }

  @Test
  void pageOverflowAndCaseSensitiveSubstringAreHandled() {
    var snapshot =
        new UserSnapshot(
            List.of(new User(2, "Ab김", 20, "M", 100), new User(1, "김나", 21, "F", 200)), List.of());
    var service = new UserService(() -> snapshot);
    service.reload();
    assertThat(service.list(0, 1).content()).extracting(User::id).containsExactly(2L);
    assertThat(service.list(Integer.MAX_VALUE, 100).content()).isEmpty();
    assertThat(service.search(" 김 ")).hasSize(2);
    assertThat(service.search("ab")).isEmpty();
  }

  @Test
  void readersSeeOldSnapshotUntilReloadCompletes() throws Exception {
    var original =
        new UserSnapshot(
            List.of(new User(1, "이전", 20, "M", 100)),
            List.of(new ParseFailure(2, "bad", "invalid")));
    var next = new UserSnapshot(List.of(new User(2, "신규", 21, "F", 200)), List.of());
    var loads = new java.util.concurrent.atomic.AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var service =
        new UserService(
            () -> {
              if (loads.getAndIncrement() == 0) return original;
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
              }
              return next;
            });
    service.reload();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var reload = executor.submit(service::reload);
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(service.list(0, 20).content()).containsExactlyElementsOf(original.users());
        assertThat(service.search("신규")).isEmpty();
        assertThat(service.find(1).name()).isEqualTo("이전");
        assertThat(service.failures()).hasSize(1);
      } finally {
        release.countDown();
      }
      assertThat(reload.get(5, TimeUnit.SECONDS)).isEqualTo(new UserService.ReloadResult(1, 0));
      assertThat(service.list(0, 20).content()).containsExactlyElementsOf(next.users());
      assertThat(service.find(2).name()).isEqualTo("신규");
      assertThat(service.failures()).isEmpty();
      assertThatThrownBy(() -> service.find(1)).isInstanceOf(UserNotFoundException.class);
    }
  }
}
