package com.geneinvoice.history;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.history.versions.ProductVersion;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HistoryTracker#touch}, the one way to version rows SQL changed: it versions what the SQL wrote even
 * when the session holds an older copy, lets go of a copy whose row SQL deleted, and with {@code lock} holds each
 * row until the transaction ends. And {@link HistoryReconciler#check()}, which never works in a caller's session.
 */
class HistoryTrackerTest extends IntegrationTestBase {

    @Autowired HistoryTracker tracker;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    User admin;
    Product widget;
    ExecutorService threads;

    @BeforeEach
    void family() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        actAs(admin);
        widget = product("Widget", "100");
        threads = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void aCopyTheSessionHoldsIsReReadSoTheVersionHasWhatTheSqlWrote() {
        String heldName = tx.execute(status -> {
            Product held = productRepository.findById(widget.getId()).orElseThrow();
            jdbc.update("update products set name = ? where id = ?", "Renamed by SQL", widget.getId());
            tracker.touch(Product.class, List.of(widget.getId()), false);
            return held.getName();
        });

        assertThat(heldName).isEqualTo("Renamed by SQL");
        assertThat(historyTestSupport.versionsOf(ProductVersion.class, widget.getId()))
                .extracting(ProductVersion::getName).containsExactly("Widget", "Renamed by SQL");
    }

    @Test
    void aCopyWhoseRowTheSqlDeletedIsLetGoAndItsHistoryClosed() {
        Product kept = product("Gizmo", "5");
        tx.executeWithoutResult(status -> {
            Product held = productRepository.findById(widget.getId()).orElseThrow();
            productRepository.findById(kept.getId()).orElseThrow();
            jdbc.update("delete from products where id = ?", held.getId());
            jdbc.update("update products set name = ? where id = ?", "Gizmo Two", kept.getId());
            tracker.touch(Product.class, List.of(widget.getId(), kept.getId()), false);
            assertThat(em.contains(held)).isFalse();
        });

        assertThat(productRepository.existsById(widget.getId())).isFalse();
        assertThat(historyTestSupport.versionsOf(ProductVersion.class, widget.getId())).singleElement()
                .satisfies(v -> assertThat(v.isOpen()).isFalse());
        assertThat(historyTestSupport.versionsOf(ProductVersion.class, kept.getId()))
                .extracting(ProductVersion::getName).containsExactly("Gizmo", "Gizmo Two");
    }

    @Test
    void touchWithLockHoldsARowTheSessionDoesNotHoldUntilCommit() throws Exception {
        touchWithLockHoldsTheRow(false);
    }

    @Test
    void touchWithLockHoldsARowTheSessionHoldsUntilCommit() throws Exception {
        touchWithLockHoldsTheRow(true);
    }

    /** A caller's unflushed change and managed copies survive a check run inside its transaction. */
    @Test
    void checkInsideATransactionLeavesTheCallersSessionAlone() {
        tx.executeWithoutResult(status -> {
            Product held = productRepository.findById(widget.getId()).orElseThrow();
            held.setName("Widget Pro");
            assertThat(historyReconciler.check().problems()).isEmpty();
            assertThat(em.contains(held)).isTrue();
        });

        assertThat(productRepository.findById(widget.getId()).orElseThrow().getName()).isEqualTo("Widget Pro");
        assertThat(historyTestSupport.versionsOf(ProductVersion.class, widget.getId()))
                .extracting(ProductVersion::getName).containsExactly("Widget", "Widget Pro");
    }

    /**
     * One transaction touches the row with {@code lock}; another's update of it waits until the first commits,
     * then goes through.
     */
    private void touchWithLockHoldsTheRow(boolean heldFirst) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = threads.submit(() -> signedIn(() -> tx.executeWithoutResult(status -> {
            if (heldFirst) productRepository.findById(widget.getId()).orElseThrow();
            tracker.touch(Product.class, List.of(widget.getId()), true);
            locked.countDown();
            await(release);
        })));
        await(locked);

        Future<?> writer = threads.submit(() -> signedIn(() -> tx.executeWithoutResult(status -> {
            jdbc.update("update products set name = ? where id = ?", "Widget Locked Out", widget.getId());
            tracker.touch(Product.class, List.of(widget.getId()), false);
        })));
        assertThatThrownBy(() -> writer.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        writer.get(5, TimeUnit.SECONDS);

        assertThat(productRepository.findById(widget.getId()).orElseThrow().getName()).isEqualTo("Widget Locked Out");
        assertThat(historyTestSupport.versionsOf(ProductVersion.class, widget.getId()))
                .extracting(ProductVersion::getName).containsExactly("Widget", "Widget Locked Out");
    }

    private Void signedIn(Runnable body) {
        actAs(admin);
        try {
            body.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
        return null;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).as("the other thread never got there").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
