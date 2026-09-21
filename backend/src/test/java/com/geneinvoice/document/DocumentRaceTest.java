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

class DocumentRaceTest extends DocumentTestBase {

    private static final long HOLD_MS = 300;

    @Autowired DocumentService documentService;
    @Autowired CustomerService customerService;
    @Autowired PlatformTransactionManager transactionManager;

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
        assertThat(deletionsOf(id)).hasSize(1);
    }

    @Test
    void anUploadThatFinishesAfterItsCustomerIsDeletedIsRefusedAndLeavesNoBytes() throws Exception {
        Customer initech = customer("Initech");
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

    private Path root() {
        return Path.of(documentProperties.getLocal().getRoot()).toAbsolutePath().normalize();
    }

    private static List<String> filesUnder(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

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
