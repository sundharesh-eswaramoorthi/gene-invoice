package com.geneinvoice.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two people writing to one document at the same moment, and what each of them is told.
 *
 * <p>Every case here is the same shape: one write reads a row, decides, and writes it back, while
 * a second write is doing the same thing to the same row. Read without a lock, both see the state
 * before either of them acted, and the pair leaves something that no sequence of the two would
 * have left — one document deleted twice, or a file attached to a customer that is no longer
 * there. Each is read under its row's write lock now, so the second one waits and then sees what
 * the first one did (DOC-3, DOC-5).
 */
class DocumentRaceTest extends DocumentTestBase {

    /** How long the first write holds its transaction open with the second one already trying. */
    private static final long HOLD_MS = 300;

    @Autowired DocumentService documentService;
    @Autowired CustomerService customerService;
    @Autowired PlatformTransactionManager transactionManager;

    // ---- deleting one document twice at once (DOC-3, AC-C3) --------------------

    @Test
    void theSecondOfTwoDeletesOfOneDocumentIsToldItIsGone() throws Exception {
        JsonNode dto = upload(admin, "INVOICE", acmeInvoice.getId(), "po.pdf");
        long id = dto.get("id").asLong();

        AtomicReference<Throwable> second = race(
                () -> documentService.delete(id),
                () -> documentService.delete(id));

        assertThat(second.get())
                .as("the loser is told the document is gone, as a second delete in a row is")
                .isNotNull()
                .hasMessageContaining(DocumentService.NOT_FOUND);

        Document row = documentRepository.findById(id).orElseThrow();
        assertThat(row.isDeleted()).isTrue();
        assertThat(row.getDeletedByUserId()).isEqualTo(admin.getId());
        // One document deleted once is one line in the record's history, not two (AC-C4).
        assertThat(deletionsOf(id)).hasSize(1);
    }

    // ---- uploading while the parent record is being deleted (DOC-5, AC-C5) -----

    /**
     * The delete got there first, so the upload loses: it is the one that can still be undone,
     * while the delete has already written the customer's own trail and taken its login and its
     * POC seats. The row is never written, and the bytes that were already stored go with it, so
     * nothing is left behind that only a database prompt could find (AC-C18).
     */
    @Test
    void anUploadThatFinishesAfterItsCustomerIsDeletedIsRefusedAndLeavesNoBytes() throws Exception {
        Customer initech = customer("Initech");
        // Taken before rather than asserted as empty after: the storage root outlives the database
        // between runs, so an earlier run's customer of this id may have left files of its own.
        Path stored = root().resolve("customer").resolve(String.valueOf(initech.getId()));
        List<String> before = filesUnder(stored);

        AtomicReference<Throwable> upload = race(
                () -> customerService.delete(initech.getId()),
                () -> documentService.upload("CUSTOMER", initech.getId(),
                        part("licence.pdf", pdf(), "application/pdf"), null, null));

        assertThat(upload.get())
                .as("the upload is told its record is gone")
                .isNotNull()
                .hasMessageContaining("Customer not found");
        assertThat(documentRepository.count()).isZero();
        assertThat(filesUnder(stored))
                .as("and the bytes it had already stored are taken back out")
                .isEqualTo(before);
    }

    /**
     * The other way round: the upload's row was already being written when the delete arrived, so
     * the delete waits the moment it takes to commit and then takes the new document with it. A
     * cascade that read the documents a moment too early would leave this one live, pointing at a
     * customer that no longer exists — downloadable by nobody and cleaned up by nothing.
     */
    @Test
    void aCustomerDeletedDuringAnUploadTakesThatUploadWithIt() throws Exception {
        Customer initech = customer("Initech");
        AtomicLong uploaded = new AtomicLong();

        AtomicReference<Throwable> delete = race(
                () -> uploaded.set(documentService.upload("CUSTOMER", initech.getId(),
                        part("licence.pdf", pdf(), "application/pdf"), null, null).id()),
                () -> customerService.delete(initech.getId()));

        assertThat(delete.get()).isNull();
        assertThat(customerRepository.findById(initech.getId())).isEmpty();
        assertThat(documentRepository.findById(uploaded.get()).orElseThrow())
                .satisfies(d -> {
                    assertThat(d.isDeleted())
                            .as("the cascade waited for the upload and took it too")
                            .isTrue();
                    assertThat(d.getDeletedAt()).isNotNull();
                });
    }

    // ---- the harness -----------------------------------------------------------

    /**
     * Runs {@code first} in a transaction held open for {@link #HOLD_MS} and {@code second} in its
     * own, started inside that window. Returns whatever the second one threw, which is the thing
     * each of these tests is about: what the write that lost the race is told.
     */
    private AtomicReference<Throwable> race(Runnable first, Runnable second) throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch firstIsInFlight = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        Thread one = new Thread(() -> {
            actAs(admin);
            try {
                transactions.executeWithoutResult(status -> {
                    first.run();
                    firstIsInFlight.countDown();
                    sleep(HOLD_MS);
                });
            } catch (Throwable t) {
                firstIsInFlight.countDown();
                firstFailure.set(t);
            }
        }, "document-race-one");

        Thread two = new Thread(() -> {
            actAs(admin);
            try {
                firstIsInFlight.await(5, TimeUnit.SECONDS);
                second.run();
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        }, "document-race-two");

        one.start();
        two.start();
        one.join(30_000);
        two.join(30_000);
        assertThat(firstFailure.get()).isNull();
        return secondFailure;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- what is left behind ---------------------------------------------------

    private Path root() {
        return Path.of(documentProperties.getLocal().getRoot()).toAbsolutePath().normalize();
    }

    /** What is in a storage directory, by name and in a fixed order, or nothing when it is not there. */
    private static List<String> filesUnder(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** The record's history rows for one document's deletion. */
    private List<JsonNode> deletionsOf(long documentId) throws Exception {
        JsonNode history = read(mockMvc.perform(get("/api/audit")
                        .param("entityType", "INVOICE")
                        .param("entityId", String.valueOf(acmeInvoice.getId()))
                        .with(as(admin)))
                .andExpect(status().isOk()));
        return StreamSupport.stream(history.spliterator(), false)
                .filter(e -> "DOCUMENT_DELETED".equals(e.get("action").asText()))
                .filter(e -> e.get("beforeJson").asText().contains("\"id\":" + documentId))
                .toList();
    }
}
