package com.paintstudio.common;

import static org.junit.jupiter.api.Assertions.*;

import com.paintstudio.common.concurrent.BackgroundJobs;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class BackgroundJobsTest {
  @Test
  void workerErrorsCompleteThePublicFuture() throws Exception {
    try (var jobs = new BackgroundJobs(1, 2)) {
      var future =
          jobs.submit(
              () -> {
                throw new AssertionError("worker failure");
              });
      var error = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
      assertInstanceOf(AssertionError.class, error.getCause());
      assertEquals(7, jobs.submit(() -> 7).get(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void cancellationInterruptsRunningWork() throws Exception {
    try (var jobs = new BackgroundJobs(1, 2)) {
      var started = new CountDownLatch(1);
      var interrupted = new CountDownLatch(1);
      var future =
          jobs.submit(
              () -> {
                started.countDown();
                try {
                  new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                  interrupted.countDown();
                }
                return null;
              });
      assertTrue(started.await(2, TimeUnit.SECONDS));
      future.cancel(true);
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }
  }

  @Test
  void queueIsBoundedAndDoesNotRunTasksOnCaller() throws Exception {
    try (var jobs = new BackgroundJobs(1, 2)) {
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var first =
          jobs.submit(
              () -> {
                started.countDown();
                release.await();
                return Thread.currentThread().getName();
              });
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var second = jobs.submit(() -> 2);
      var third = jobs.submit(() -> 3);
      assertThrows(ExecutionException.class, () -> third.get(2, TimeUnit.SECONDS));
      release.countDown();
      assertEquals("paint-studio-io", first.get(2, TimeUnit.SECONDS));
      assertEquals(2, second.get(2, TimeUnit.SECONDS));
    }
  }
}
