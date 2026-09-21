package com.geneinvoice.mail.message;

import com.geneinvoice.mail.FakeGoogle;
import com.geneinvoice.mail.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SendSweeperTest extends IntegrationTestBase {

    @Autowired SendSweeper sweeper;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void janeIsConnected() {
        connect("7", "Jane Doe", "jane@gmail.com");
        google.on("POST", FakeGoogle.api("/messages/send"), 200, "{\"id\":\"gm-1\",\"threadId\":\"th-1\"}");
    }

    private long claimedAndAbandoned(String externalId) {
        submit(submission("7", "Jane Doe", "91", false, copy(externalId, "Bob", "bob@acme.com")));
        long id = message(externalId).getId();
        queue.take();
        Integer claimed = transactions.execute(status -> messageRepository.claim(id, clock.instant()));
        assertThat(claimed).isEqualTo(1);
        return id;
    }

    @Test
    void aSendNobodyFinishedIsMarkedFailedAndLookedForBeforeARetrySendsIt() {
        long id = claimedAndAbandoned("gi-91-1");

        clock.advance(Duration.ofMinutes(10));
        sweeper.sweep();
        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.SENDING);

        clock.advance(Duration.ofSeconds(1));
        sweeper.sweep();
        MailMessage failed = message("gi-91-1");
        assertThat(failed.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(failed.getError()).isEqualTo("Sending was interrupted; retry to send again");
        assertThat(failed.isDeliveryUncertain()).isTrue();
        assertThat(statusTrail("gi-91-1")).containsExactly("QUEUED@1", "FAILED@3");
        assertThat(queue.enqueued()).isEmpty();

        google.on("GET", FakeGoogle.api("/messages"), 200, "{\"messages\":[{\"id\":\"gm-0\",\"threadId\":\"th-0\"}]}");
        submit(submission("7", "Jane Doe", "91", true, copy("gi-91-1", "Bob", "bob@acme.com")));
        work();
        assertThat(message("gi-91-1").getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(message("gi-91-1").getProviderMessageId()).isEqualTo("gm-0");
        assertThat(google.requests("POST", FakeGoogle.api("/messages/send"))).isEmpty();
        assertThat(message("gi-91-1").getId()).isEqualTo(id);
    }

    @Test
    void aQueuedCopyWhoseMessageWasLostIsPublishedAgain() {
        queue.failing(true);
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-1", "Bob", "bob@acme.com")));
        long lost = message("gi-91-1").getId();
        queue.failing(false);
        submit(submission("7", "Jane Doe", "92", false, copy("gi-92-1", "Bob", "bob@acme.com")));
        long onItsWay = message("gi-92-1").getId();
        queue.take();

        sweeper.sweep();
        assertThat(queue.take()).containsExactly(lost);
        assertThat(message("gi-91-1").getEnqueuedAt()).isEqualTo(T0);

        clock.advance(Duration.ofMinutes(2));
        sweeper.sweep();
        assertThat(queue.take()).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        sweeper.sweep();
        assertThat(queue.take()).containsExactlyInAnyOrder(lost, onItsWay);
    }

    @Test
    void aRetryWaitingForItsTimeIsLeftToTheDelayQueue() {
        google.on("POST", FakeGoogle.api("/messages/send"), 503, FakeGoogle.gmailError(503, "Backend Error"));
        submit(submission("7", "Jane Doe", "91", false, copy("gi-91-1", "Bob", "bob@acme.com")));
        long id = message("gi-91-1").getId();
        work();
        assertThat(message("gi-91-1").getNextAttemptAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));

        clock.advance(Duration.ofMinutes(2));
        sweeper.sweep();
        assertThat(queue.take()).isEmpty();

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        sweeper.sweep();
        assertThat(queue.take()).isEqualTo(List.of(id));
    }
}
