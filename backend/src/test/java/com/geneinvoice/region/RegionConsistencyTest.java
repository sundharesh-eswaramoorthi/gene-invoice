package com.geneinvoice.region;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.time.Today;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every record is in its customer's region (I1): whatever is created, paid, promised, disputed, tasked or moved,
 * no child row ever names a region its customer is not in.
 */
class RegionConsistencyTest extends IntegrationTestBase {

    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager em;

    User admin;
    User sam;
    User cara;
    Region north;
    Region south;
    Product widget;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        grant(admin, south, AccessLevel.APPROVE, RecordScope.ALL);
        sam = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        cara = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        for (Region r : List.of(north, south)) {
            grant(sam, r, AccessLevel.READ, RecordScope.BOOK);
            grant(cara, r, AccessLevel.READ, RecordScope.BOOK);
        }
        widget = product("Widget", "100.00");
    }

    /** Child rows whose region is not their customer's, as {@code "table id"}. */
    private List<String> strays() {
        return tx.execute(status -> {
            List<String> strays = new ArrayList<>();
            for (String entity : List.of("Invoice", "Payment", "PaymentPromise")) {
                em.createQuery("select e.id from " + entity + " e join e.customer c where e.region <> c.region"
                                + " or (e.region is null and c.region is not null)", Long.class)
                        .getResultList().forEach(id -> strays.add(entity + " " + id));
            }
            em.createQuery("select d.id from Dispute d, Customer c where c.id = d.customerId and"
                            + " (d.region <> c.region or (d.region is null and c.region is not null))", Long.class)
                    .getResultList().forEach(id -> strays.add("Dispute " + id));
            em.createQuery("select t.id from Task t, Customer c where c.id = t.customerId and t.region <> c.region",
                            Long.class)
                    .getResultList().forEach(id -> strays.add("Task " + id));
            return strays;
        });
    }

    private JsonNode postOk(User caller, String path, Object body) throws Exception {
        ResultActions result = mockMvc.perform(post(path).with(as(caller))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk());
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** A customer with an invoice, a payment against it, a promise on it, a dispute about it and tasks on them. */
    private long family(String name, Region region) throws Exception {
        long customerId = postOk(admin, "/api/customers", Map.of("name", name, "username",
                name.toLowerCase().replace(' ', '.'), "password", "Secret123!", "regionId", region.getId()))
                .get("id").asLong();
        long invoiceId = postOk(admin, "/api/invoices", Map.of("customerId", customerId, "salesPocUserId", sam.getId(),
                "items", List.of(Map.of("productId", widget.getId(), "quantity", 2)))).get("id").asLong();
        postOk(admin, "/api/payments", Map.of("customerId", customerId, "amount", 50, "method", "Cash",
                "invoiceIds", List.of(invoiceId), "collectionPocUserId", cara.getId()));
        postOk(admin, "/api/promises", Map.of("customerId", customerId, "amount", 100,
                "promisedDate", Today.date().plusDays(5).toString(), "collectionPocUserId", cara.getId(),
                "invoiceIds", List.of(invoiceId)));
        User login = userRepository.findByCustomerId(customerId).orElseThrow();
        postOk(login, "/api/disputes", Map.of("targetType", "INVOICE", "targetId", invoiceId,
                "reason", "Wrong quantity"));
        postOk(admin, "/api/tasks", Map.of("entityType", "CUSTOMER", "entityId", customerId, "title", "Call them"));
        postOk(admin, "/api/tasks", Map.of("entityType", "INVOICE", "entityId", invoiceId, "title", "Chase it"));
        return customerId;
    }

    @Test
    void noRecordIsEverInARegionItsCustomerIsNotIn() throws Exception {
        long acme = family("Acme Ltd", north);
        long globex = family("Globex Corp", south);
        assertThat(strays()).isEmpty();

        postOk(admin, "/api/customers/" + acme + "/move-region", Map.of("regionId", south.getId()));
        assertThat(strays()).isEmpty();
        postOk(admin, "/api/customers/" + globex + "/move-region", Map.of("regionId", north.getId()));
        assertThat(strays()).isEmpty();

        long afterwards = postOk(admin, "/api/invoices", Map.of("customerId", acme, "salesPocUserId", sam.getId(),
                "items", List.of(Map.of("productId", widget.getId(), "quantity", 1)))).get("id").asLong();
        assertThat(invoiceRepository.findById(afterwards).orElseThrow().getRegion().getId())
                .isEqualTo(south.getId());
        assertThat(strays()).isEmpty();
        assertThat(jdbc.queryForObject("select count(distinct region_id) from invoices", Long.class))
                .as("both regions have invoices").isEqualTo(2);
        long task = postOk(admin, "/api/tasks", Map.of("entityType", "INVOICE", "entityId", afterwards,
                "title", "New one")).get("id").asLong();
        assertThat(taskRepository.findById(task).orElseThrow().getRegion().getId()).isEqualTo(south.getId());
        assertThat(jdbc.queryForObject("select count(distinct region_id) from tasks", Long.class))
                .as("both regions have tasks").isEqualTo(2);
        assertThat(strays()).isEmpty();
    }

    /** The check does find a stray: one planted with SQL, and put back before the test ends. */
    @Test
    void theCheckFindsAStrayRow() throws Exception {
        long acme = family("Acme Ltd", north);
        Payment paid = paymentRepository.findAll().stream()
                .filter(p -> p.getCustomer().getId().equals(acme)).findFirst().orElseThrow();

        jdbc.update("update payments set region_id = ? where id = ?", south.getId(), paid.getId());
        try {
            assertThat(strays()).containsExactly("Payment " + paid.getId());
        } finally {
            jdbc.update("update payments set region_id = ? where id = ?", north.getId(), paid.getId());
        }
        assertThat(strays()).isEmpty();

        Long task = taskRepository.findAll().get(0).getId();
        jdbc.update("update tasks set region_id = ? where id = ?", south.getId(), task);
        try {
            assertThat(strays()).containsExactly("Task " + task);
        } finally {
            jdbc.update("update tasks set region_id = ? where id = ?", north.getId(), task);
        }
        assertThat(strays()).isEmpty();
    }
}
