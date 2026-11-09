package com.paintstudio.document.service;

import com.paintstudio.app.config.EditorConfig;
import com.paintstudio.common.concurrent.BackgroundJobs;
import com.paintstudio.common.logging.*;
import com.paintstudio.document.model.*;
import com.paintstudio.document.persistence.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class DocumentService {
  private final BackgroundJobs jobs;
  private final EditorConfig policy;

  public DocumentService(BackgroundJobs jobs, EditorConfig policy) {
    this.jobs = jobs;
    this.policy = policy;
  }

  public CompletableFuture<DocumentSnapshot> open(Path path) {
    return jobs.submit(
        () -> {
          long start = System.nanoTime();
          var d = new ProjectReader(policy).read(path);
          log("document.opened", d, start);
          return d;
        });
  }

  public CompletableFuture<Long> save(Path path, DocumentSnapshot snapshot) {
    return jobs.submit(
        () -> {
          long start = System.nanoTime();
          new ProjectWriter().write(path, snapshot, policy.maxFileMiB() * 1024L * 1024);
          log("document.saved", snapshot, start);
          return snapshot.revision();
        });
  }

  private void log(String event, DocumentSnapshot d, long start) {
    AppLogger.getInstance()
        .info(
            LogCategory.DOCUMENT,
            DocumentService.class,
            event,
            Map.of(
                "documentId",
                d.id(),
                "revision",
                d.revision(),
                "elapsedMs",
                (System.nanoTime() - start) / 1000000));
  }
}
