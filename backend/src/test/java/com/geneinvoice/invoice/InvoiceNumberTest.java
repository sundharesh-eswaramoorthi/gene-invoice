package com.geneinvoice.invoice;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceNumberTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;

    @Test
    void concurrentCreatesGetDistinctConsecutiveNumbers() throws Exception {
        User sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        Customer acme = customer("Acme Ltd");
        Product widget = product("Widget", "100.00");
        actAs(userRepository.findByUsername("admin").orElseThrow());
        Authentication admin = SecurityContextHolder.getContext().getAuthentication();

        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> created = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                created.add(pool.submit(() -> {
                    SecurityContextHolder.getContext().setAuthentication(admin);
                    try {
                        start.await();
                        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(
                                acme.getId(), null, null, sales.getId(),
                                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))))
                                .getInvoiceNumber();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            start.countDown();
            List<String> numbers = new ArrayList<>();
            for (Future<String> f : created) numbers.add(f.get(30, TimeUnit.SECONDS));

            String today = "INV-" + LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
            assertThat(numbers).doesNotHaveDuplicates().allMatch(s -> s.startsWith(today));
            List<Integer> suffixes = numbers.stream()
                    .map(s -> Integer.parseInt(s.substring(today.length()))).sorted().toList();
            assertThat(suffixes.get(n - 1) - suffixes.get(0)).isEqualTo(n - 1);
        } finally {
            pool.shutdownNow();
        }
    }
}
