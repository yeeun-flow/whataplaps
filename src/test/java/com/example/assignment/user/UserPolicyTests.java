package com.example.assignment.user;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.assignment.AssignmentApplication;
import com.example.assignment.user.application.*;
import com.example.assignment.user.domain.User;
import com.example.assignment.user.infrastructure.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

class UserPolicyTests {
  @TempDir Path directory;

  @Test
  void originalFilePaginatesAndCorrectedFileReplacesFailures() throws Exception {
    var file = directory.resolve("user.txt");
    String original = Files.readString(Path.of("src/test/resources/users-fixture.txt"));
    Files.writeString(file, original);
    var service = service(file);
    assertThat(service.reload()).isEqualTo(new UserService.ReloadResult(37, 3));
    assertThat(service.list(0, 20).content()).hasSize(20);
    assertThat(service.list(1, 20).content()).hasSize(17);
    assertThat(service.list(2, 20).content()).isEmpty();
    assertThat(service.list(0, 100).content()).hasSize(37);
    assertThat(service.list(Integer.MAX_VALUE, 100).content()).isEmpty();
    Files.writeString(
        file,
        original
            .replace("45_OOO_000", "45_000_000")
            .replace("F ,,", "F ,")
            .replace("68_AAA_000", "68_000_000"));
    assertThat(service.reload()).isEqualTo(new UserService.ReloadResult(40, 0));
    assertThat(service.reload()).isEqualTo(new UserService.ReloadResult(40, 0));
    assertThat(service.find(1040).name()).isEqualTo("오뚜기");
  }

  @Test
  void snapshotAndPreviouslyReturnedResultsRemainImmutableAfterReload() {
    var user = new User(1, "기존", 20, "X", 100);
    var failure = new ParseFailure(2, "bad", "invalid");
    var users = new ArrayList<>(List.of(user));
    var failures = new ArrayList<>(List.of(failure));
    var original = new UserSnapshot(users, failures);
    users.clear();
    failures.clear();
    assertThat(original.users()).containsExactly(user);
    assertThat(original.failures()).containsExactly(failure);
    assertThat(original.byId()).containsEntry(1L, user);
    assertThatThrownBy(() -> original.users().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> original.failures().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> original.byId().clear())
        .isInstanceOf(UnsupportedOperationException.class);

    var next = new java.util.concurrent.atomic.AtomicReference<>(original);
    var service = new UserService(next::get);
    service.reload();
    var oldPage = service.list(0, 20);
    var oldFailures = service.failures();
    next.set(new UserSnapshot(List.of(new User(3, "신규", 21, "X", 200)), List.of()));
    service.reload();
    assertThat(service.list(0, 20).content()).extracting(User::id).containsExactly(3L);
    assertThat(service.failures()).isEmpty();
    assertThat(oldPage.content()).containsExactly(user);
    assertThat(oldFailures).containsExactly(failure);
    assertThatThrownBy(() -> oldPage.content().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsInvalidPaginationInService() {
    var service = new UserService(() -> new UserSnapshot(List.of(), List.of()));
    for (int[] query : new int[][] {{-1, 20}, {0, 0}, {0, -1}, {0, 101}, {0, Integer.MAX_VALUE}}) {
      assertThatThrownBy(() -> service.list(query[0], query[1]))
          .isInstanceOf(InvalidUserQueryException.class);
    }
  }

  @Test
  void allInvalidAndEmptyFilesReplaceOldSnapshot() throws Exception {
    var file = directory.resolve("user.txt");
    Files.writeString(file, "1,기존,20,X,100\n");
    var service = service(file);
    service.reload();
    Files.writeString(file, "bad\n1,이름,20,F,\n");
    assertThat(service.reload()).isEqualTo(new UserService.ReloadResult(0, 2));
    assertThatThrownBy(() -> service.find(1)).isInstanceOf(UserNotFoundException.class);
    assertThat(service.failures()).extracting(ParseFailure::lineNumber).containsExactly(1, 2);
    Files.writeString(file, "");
    assertThat(service.reload()).isEqualTo(new UserService.ReloadResult(0, 0));
  }

  @Test
  void readFailureAfterValidRowsPreservesUsersAndFailuresTogether() throws Exception {
    var file = directory.resolve("user.txt");
    Files.writeString(file, "1,기존,20,X,100\nbad\n");
    var service = new UserService(new TextUserFileLoader(file.toString()));
    service.reload();
    var before = service.list(0, 20);
    var failuresBefore = service.failures();
    // Exceed the reader buffer before malformed UTF-8 to exercise failure mid-file.
    Files.writeString(file, "2,새이름,21,F,200\n".repeat(2000));
    Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28}, StandardOpenOption.APPEND);
    assertThatThrownBy(service::reload).isInstanceOf(UserFileReadException.class);
    assertThat(service.list(0, 20)).isEqualTo(before);
    assertThat(service.failures()).isSameAs(failuresBefore);
    assertThat(service.find(1).name()).isEqualTo("기존");
    assertThat(service.failures())
        .containsExactly(new ParseFailure(2, "bad", "필드는 ID, 이름, 나이, 성별, 연봉 5개여야 합니다."));
    Files.delete(file);
    assertThatThrownBy(service::reload).isInstanceOf(UserFileReadException.class);
    assertThat(service.list(0, 20)).isEqualTo(before);
    assertThat(service.failures()).isSameAs(failuresBefore);
  }

  @Test
  void acceptsNumericBoundsAndUnrestrictedNonemptyGender() {
    var user =
        new UserLineParser().parse("9223372036854775807,이름,2147483647,미지정,9223372036854775807");
    assertThat(user)
        .isEqualTo(new User(Long.MAX_VALUE, "이름", Integer.MAX_VALUE, "미지정", Long.MAX_VALUE));
  }

  @Test
  void malformedFirstOccurrenceDoesNotReserveId() throws Exception {
    var file = directory.resolve("user.txt");
    Files.writeString(file, "1,오류,20,F,wrong\n1,정상,20,X,100\n1,중복,21,F,200\n");
    var snapshot = new TextUserFileLoader(file.toString()).load();
    assertThat(snapshot.users()).extracting(User::name).containsExactly("정상");
    assertThat(snapshot.failures()).extracting(ParseFailure::lineNumber).containsExactly(1, 3);
  }

  @Test
  void parsingFailuresAreLoggedWithLineAndReason() {
    Logger logger = (Logger) LoggerFactory.getLogger(TextUserFileLoader.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      new TextUserFileLoader("src/test/resources/users-fixture.txt").load();
      var warnings =
          appender.list.stream().filter(e -> e.getLevel().toString().equals("WARN")).toList();
      assertThat(warnings).hasSize(3);
      assertThat(warnings.get(0).getFormattedMessage()).contains("line=16", "reason=", "연봉");
      assertThat(warnings.get(1).getFormattedMessage()).contains("line=31", "5개");
      assertThat(warnings.get(2).getFormattedMessage()).contains("line=39", "연봉");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "directory", "invalid-utf8"})
  void fileReadErrorsLogOriginalCauseAndStack(String scenario) throws Exception {
    var path = directory.resolve("input.txt");
    if (scenario.equals("directory")) Files.createDirectory(path);
    if (scenario.equals("invalid-utf8")) Files.write(path, new byte[] {(byte) 0xc3, (byte) 0x28});
    Logger logger = (Logger) LoggerFactory.getLogger(TextUserFileLoader.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var failure = catchThrowable(() -> new TextUserFileLoader(path.toString()).load());
      assertThat(failure).isInstanceOf(UserFileReadException.class);
      assertThat(failure.getCause()).isInstanceOf(java.io.IOException.class);
      var errors =
          appender.list.stream()
              .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
              .toList();
      assertThat(errors).hasSize(1);
      var logged = errors.get(0).getThrowableProxy();
      assertThat(logged).isNotNull();
      assertThat(logged.getClassName()).isEqualTo(failure.getCause().getClass().getName());
      assertThat(logged.getMessage()).isEqualTo(failure.getCause().getMessage());
      assertThat(logged.getStackTraceElementProxyArray()).isNotEmpty();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void missingFileFailsApplicationStartup() {
    var app = new SpringApplication(AssignmentApplication.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setRegisterShutdownHook(false);
    assertThatThrownBy(
            () -> {
              try (var ignored = app.run("--app.users.file=" + directory.resolve("missing.txt"))) {
                fail("Application must not start without its input file");
              }
            })
        .hasRootCauseInstanceOf(NoSuchFileException.class);
  }

  @Test
  void concurrentReloadsExecuteSeriallyAndPublishLastCompletedResult() throws Exception {
    var firstThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    var secondThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
    var calls = new AtomicInteger();
    var active = new AtomicInteger();
    var maximum = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var service =
        new UserService(
            () -> {
              maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
              int id = calls.incrementAndGet();
              try {
                if (id == 1) {
                  firstThread.set(Thread.currentThread());
                  entered.countDown();
                  if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Timed out");
                }
                return new UserSnapshot(List.of(new User(id, "사용자", 0, "X", 0)), List.of());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
              } finally {
                active.decrementAndGet();
              }
            });
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(service::reload);
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var attempted = new CountDownLatch(1);
        var second =
            executor.submit(
                () -> {
                  secondThread.set(Thread.currentThread());
                  attempted.countDown();
                  return service.reload();
                });
        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
        // Keep the first load paused until the second thread is blocked on this service's monitor.
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean contended = false;
        while (System.nanoTime() < deadline) {
          var info = threads.getThreadInfo(secondThread.get().threadId());
          if (info != null
              && info.getThreadState() == Thread.State.BLOCKED
              && info.getLockOwnerId() == firstThread.get().threadId()
              && info.getLockInfo() != null
              && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(service)) {
            contended = true;
            break;
          }
          if (second.isDone()) break;
          Thread.yield();
        }
        assertThat(contended)
            .as("Second reload must contend for the service monitor while the first load is paused")
            .isTrue();
        assertThat(calls.get()).isEqualTo(1);
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertThat(maximum.get()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(service.list(0, 20).content()).extracting(User::id).containsExactly(2L);
      } finally {
        release.countDown();
      }
    }
  }

  private UserService service(Path file) {
    return new UserService(new TextUserFileLoader(file.toString()));
  }
}
