package com.geneinvoice.mail.message;

import java.time.Duration;

public interface SendQueue {

    record Item(long id) {}

    void enqueue(long id);

    void enqueueRetry(long id, Duration delay);
}
