package com.geneinvoice.poc;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotesOnlySaveTest extends IntegrationTestBase {

    @Autowired InvoiceService invoiceService;
    @Autowired PaymentService paymentService;
    @Autowired PrivilegeRepository privilegeRepository;

    User admin;
    User sales;
    User collector;
    Invoice invoice;
    Payment payment;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        collector = user("cara.collections", DataSeeder.ROLE_COLLECTION_POC);
        Customer acme = customer("Acme Ltd");
        Product widget = product("Widget", "100.00");
        actAs(admin);
        invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null,
                sales.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
        payment = paymentService.record(new PaymentDtos.CreatePaymentRequest(acme.getId(),
                new BigDecimal("40.00"), "Cash", null, null, collector.getId(), null));
    }

    private User clerk() {
        Role role = roleRepository.findByName("NOTES_CLERK").orElseGet(() -> roleRepository.save(
                Role.builder()
                        .name("NOTES_CLERK")
                        .description("Edits records but may not assign POCs")
                        .privileges(Stream.of(Privileges.INVOICE_VIEW, Privileges.INVOICE_MANAGE,
                                        Privileges.PAYMENT_VIEW, Privileges.PAYMENT_MANAGE,
                                        Privileges.SCOPE_OVERRIDE)
                                .map(n -> privilegeRepository.findByName(n).orElseThrow())
                                .collect(Collectors.toCollection(HashSet::new)))
                        .build()));
        return user("nora.notes", role.getName());
    }

    private ResultActions save(String path, User caller, Map<String, Object> body) throws Exception {
        return mockMvc.perform(patch(path).with(as(caller))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    @Test
    void notesSaveWhenTheNamedPocHasBeenDeactivated() throws Exception {
        sales.setActive(false);
        userRepository.save(sales);
        collector.setActive(false);
        userRepository.save(collector);

        save("/api/invoices/" + invoice.getId(), admin,
                Map.of("notes", "invoice note", "salesPocUserId", sales.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("invoice note"));
        save("/api/payments/" + payment.getId(), admin,
                Map.of("notes", "payment note", "collectionPocUserId", collector.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("payment note"));
    }

    @Test
    void notesSaveForARoleThatMayNotAssignPocs() throws Exception {
        User clerk = clerk();
        save("/api/invoices/" + invoice.getId(), clerk,
                Map.of("notes", "invoice note", "salesPocUserId", sales.getId()))
                .andExpect(status().isOk());
        save("/api/payments/" + payment.getId(), clerk,
                Map.of("notes", "payment note", "collectionPocUserId", collector.getId()))
                .andExpect(status().isOk());
    }

    @Test
    void actuallyChangingThePocStillNeedsPocAssign() throws Exception {
        User clerk = clerk();
        User otherSales = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        User otherCollector = user("cole.collections", DataSeeder.ROLE_COLLECTION_POC);

        save("/api/invoices/" + invoice.getId(), clerk,
                Map.of("notes", "n", "salesPocUserId", otherSales.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You may not change the Sales POC"));
        save("/api/payments/" + payment.getId(), clerk,
                Map.of("notes", "n", "collectionPocUserId", otherCollector.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You may not change the Collection POC"));
    }

    @Test
    void changingToADeactivatedPocIsStillRefused() throws Exception {
        User retired = user("rita.retired", DataSeeder.ROLE_SALES_POC);
        retired.setActive(false);
        userRepository.save(retired);

        save("/api/invoices/" + invoice.getId(), admin,
                Map.of("notes", "n", "salesPocUserId", retired.getId()))
                .andExpect(status().isBadRequest());
    }
}
