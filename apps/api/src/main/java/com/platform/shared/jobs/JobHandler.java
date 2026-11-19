package com.platform.shared.jobs;

/** Handlers run inside the same transaction that marks the job DONE, so they must be idempotent. */
public interface JobHandler {
    String type();

    void handle(Job job);
}
