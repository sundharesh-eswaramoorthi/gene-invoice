package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A customer, an invoice, a product, a payment, a promise and a dispute opened as of each day of the
 * {@link AsOfScenario}: the record as it was, its lines, allocations, links and figures as they were, nothing that can
 * be changed, and not found on a day before it existed.
 */
class AsOfDetailTest extends AsOfScenario {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EmailTargets emailTargets;

    @Test
    void aCustomerOpensAsItWasThatDay() throws Exception {
        JsonNode d2 = read("/api/customers/" + acme.getId(), D2);
        assertThat(d2.get("name").asText()).isEqualTo("Acme Ltd");
        assertThat(d2.get("regionId").asLong()).isEqualTo(north.getId());
        assertThat(d2.get("regionName").asText()).isEqualTo("North");
        assertThat(d2.get("outstanding").decimalValue()).isEqualByComparingTo("1100");
        assertThat(d2.get("overdueAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(d2.at("/successPocs/0/user/username").asText()).isEqualTo("sam.sales");
        assertThat(d2.at("/successPocs/0/primary").asBoolean()).isTrue();
        assertThat(d2.get("username").asText()).as("the login is named as it is now").isEqualTo("acme.login");
        assertThat(d2.get("canManage").asBoolean()).isFalse();
        assertThat(d2.get("canMoveRegion").asBoolean()).isFalse();
        assertThat(d2.has("version")).isFalse();

        JsonNode d3 = read("/api/customers/" + acme.getId(), D3);
        assertThat(d3.get("name").asText()).isEqualTo("Zeta Holdings");
        assertThat(d3.get("regionCode").asText()).isEqualTo("SOUTH");
        assertThat(d3.get("overdueAmount").decimalValue()).isEqualByComparingTo("600");
        assertThat(d3.get("successPocs")).isEmpty();

        JsonNode live = read("/api/customers/" + acme.getId(), null);
        assertThat(live.get("outstanding").decimalValue()).isEqualByComparingTo("1000");
        assertThat(live.get("canManage").asBoolean()).isTrue();
        assertThat(live.get("version").isNumber()).isTrue();
    }

    @Test
    void aRecordIsNotFoundOnADayBeforeItExisted() throws Exception {
        call(get("/api/customers/" + acme.getId()), admin, D0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Customer not found"));
        call(get("/api/customers/" + gamma.getId()), admin, D2).andExpect(status().isNotFound());
        call(get("/api/invoices/" + x), admin, D0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Invoice not found"));
        call(get("/api/products/" + gadget.getId()), admin, D2)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found"));
        call(get("/api/customers/" + gamma.getId()), admin, D3).andExpect(status().isOk());
    }

    @Test
    void anInvoiceOpensWithItsLinesFiguresAndCustomerAsTheyWere() throws Exception {
        JsonNode d1 = read("/api/invoices/" + x, D1);
        assertThat(d1.get("status").asText()).isEqualTo("UNPAID");
        assertThat(d1.get("paidAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(d1.get("customerName").asText()).isEqualTo("Acme Ltd");
        assertThat(d1.at("/items/0/productName").asText()).isEqualTo("Widget");
        assertThat(d1.at("/items/0/lineTotal").decimalValue()).isEqualByComparingTo("1000");
        assertThat(d1.at("/salesPoc/username").asText()).isEqualTo("sam.sales");
        assertThat(d1.get("canManage").asBoolean()).isFalse();
        assertThat(d1.get("version").isNull()).isTrue();

        JsonNode d2 = read("/api/invoices/" + x, D2);
        assertThat(d2.get("status").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(d2.get("paidAmount").decimalValue()).isEqualByComparingTo("400");
        assertThat(d2.get("overdue").asBoolean()).isFalse();

        JsonNode d3 = read("/api/invoices/" + x, D3);
        assertThat(d3.get("customerName").asText()).isEqualTo("Zeta Holdings");
        assertThat(d3.get("regionCode").asText()).isEqualTo("SOUTH");
        assertThat(d3.at("/items/0/productName").asText()).as("the product was renamed on D3").isEqualTo("Widget Pro");
        assertThat(d3.get("overdue").asBoolean()).isTrue();
        assertThat(d3.get("daysOverdue").asInt()).isEqualTo(1);

        JsonNode d4 = read("/api/invoices/" + x, D4);
        assertThat(d4.get("status").asText()).isEqualTo("UNPAID");
        assertThat(d4.get("balance").decimalValue()).isEqualByComparingTo("1000");
    }

    @Test
    void anInvoicesLinesAreTheLinesOfThatDay() throws Exception {
        JsonNode d1 = read("/api/invoices/" + y, D1);
        assertThat(d1.get("items")).hasSize(1);
        assertThat(d1.at("/items/0/quantity").asInt()).isEqualTo(1);
        assertThat(d1.at("/items/0/unitPrice").decimalValue()).isEqualByComparingTo("500");
        Long lineOnD1 = d1.at("/items/0/id").asLong();

        JsonNode d2 = read("/api/invoices/" + y, D2);
        assertThat(d2.get("items")).hasSize(1);
        assertThat(d2.at("/items/0/quantity").asInt()).isEqualTo(2);
        assertThat(d2.at("/items/0/unitPrice").decimalValue()).isEqualByComparingTo("250");
        assertThat(d2.at("/items/0/productId").asLong()).isEqualTo(widget.getId());
        assertThat(d2.at("/items/0/id").asLong()).as("the lines were replaced").isNotEqualTo(lineOnD1);
        assertThat(d2.get("total").decimalValue()).isEqualByComparingTo("500");

        assertThat(read("/api/invoices/" + y, D3).get("status").asText()).isEqualTo("UNPAID");
        assertThat(read("/api/invoices/" + y, D4).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void aProductOpensAsItWas() throws Exception {
        assertThat(read("/api/products/" + widget.getId(), D2).get("name").asText()).isEqualTo("Widget");
        assertThat(read("/api/products/" + widget.getId(), D3).get("name").asText()).isEqualTo("Widget Pro");
        assertThat(read("/api/products/" + widget.getId(), null).get("name").asText()).isEqualTo("Widget Pro");
    }

    @Test
    void aPaymentOpensWithTheAllocationsOfThatDay() throws Exception {
        call(get("/api/payments/" + paymentId), admin, D1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Payment not found"));

        JsonNode d2 = read("/api/payments/" + paymentId, D2);
        assertThat(d2.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(d2.get("customerName").asText()).isEqualTo("Acme Ltd");
        assertThat(d2.get("regionCode").asText()).isEqualTo("NORTH");
        assertThat(d2.get("customerCreditBalance").decimalValue()).isEqualByComparingTo("0");
        assertThat(d2.get("invoices")).hasSize(1);
        assertThat(d2.at("/invoices/0/invoiceNumber").asText()).startsWith("INV-");
        assertThat(d2.at("/invoices/0/allocatedAmount").decimalValue()).isEqualByComparingTo("400");
        assertThat(d2.at("/invoices/0/balance").decimalValue()).isEqualByComparingTo("600");
        assertThat(d2.at("/collectionPoc/username").asText()).isEqualTo("colin.collections");
        assertThat(d2.get("canManage").asBoolean()).isFalse();

        JsonNode d3 = read("/api/payments/" + paymentId, D3);
        assertThat(d3.get("customerName").asText()).isEqualTo("Zeta Holdings");
        assertThat(d3.get("regionCode").asText()).isEqualTo("SOUTH");
        assertThat(d3.at("/invoices/0/allocatedAmount").decimalValue()).isEqualByComparingTo("400");

        JsonNode d4 = read("/api/payments/" + paymentId, D4);
        assertThat(d4.get("status").asText()).isEqualTo("VOIDED");
        assertThat(d4.get("invoices")).isEmpty();

        JsonNode live = read("/api/payments/" + paymentId, null);
        assertThat(live.get("status").asText()).isEqualTo("VOIDED");
        assertThat(live.get("canManage").asBoolean()).isTrue();
    }

    @Test
    void aPromiseOpensWithTheInvoicesAndPaymentsItWasLinkedToThatDay() throws Exception {
        call(get("/api/promises/" + promiseId), admin, D1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Payment promise not found"));

        JsonNode d2 = read("/api/promises/" + promiseId, D2);
        assertThat(d2.get("status").asText()).isEqualTo("PARTIALLY_KEPT");
        assertThat(d2.get("remainingAmount").decimalValue()).isEqualByComparingTo("200");
        assertThat(d2.get("invoices")).hasSize(1);
        assertThat(d2.at("/invoices/0/status").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(d2.get("payments")).hasSize(1);
        assertThat(d2.at("/payments/0/amount").decimalValue()).isEqualByComparingTo("400");
        assertThat(d2.get("updatedAt").asText()).as("kept on the version").isEqualTo(D2 + "T10:00:00Z");
        assertThat(d2.get("createdByUserId").asLong()).isEqualTo(admin.getId());
        assertThat(d2.get("canManage").asBoolean()).isFalse();
        assertThat(d2.get("canOverride").asBoolean()).isFalse();

        JsonNode d4 = read("/api/promises/" + promiseId, D4);
        assertThat(d4.get("status").asText()).isEqualTo("OPEN");
        assertThat(d4.get("payments")).isEmpty();
        assertThat(d4.at("/invoices/0/status").asText()).isEqualTo("UNPAID");
        assertThat(d4.get("customerName").asText()).isEqualTo("Zeta Holdings");

        JsonNode live = read("/api/promises/" + promiseId, null);
        assertThat(live.get("canManage").asBoolean()).isTrue();
    }

    @Test
    void aDisputeOpensAsItWasAndNamesItsCustomerAndTargetAsTheyWere() throws Exception {
        Long betaDispute = betaDisputesB();
        call(get("/api/disputes/" + betaDispute), admin, D1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Dispute not found"));
        call(get("/api/disputes/" + disputeId), admin, D3).andExpect(status().isNotFound());

        JsonNode d2 = read("/api/disputes/" + betaDispute, D2);
        assertThat(d2.get("status").asText()).isEqualTo("PENDING");
        assertThat(d2.get("customerName").asText()).isEqualTo("Beta Co");
        assertThat(d2.get("targetSummary").asText()).endsWith(" — 100.00");
        assertThat(d2.get("targetAmount").decimalValue()).isEqualByComparingTo("100");
        assertThat(d2.get("adminNotes").isNull()).isTrue();
        assertThat(d2.get("canResolve").asBoolean()).isFalse();

        JsonNode d3 = read("/api/disputes/" + betaDispute, D3);
        assertThat(d3.get("status").asText()).isEqualTo("DENIED");
        assertThat(d3.get("customerName").asText()).isEqualTo("Beta Group");
        assertThat(d3.get("targetSummary").asText()).endsWith(" — 150.00");
        assertThat(d3.get("resolvedAt").asText()).isEqualTo(D3 + "T10:00:00Z");

        JsonNode acmeD4 = read("/api/disputes/" + disputeId, D4);
        assertThat(acmeD4.get("status").asText()).isEqualTo("APPROVED");
        assertThat(acmeD4.get("customerName").asText()).isEqualTo("Zeta Holdings");
        assertThat(acmeD4.get("targetSummary").asText()).isEqualTo("Payment #" + paymentId + " — 400.00");
    }

    /**
     * A customer's credit, named as the customer was named that day, with the balance of that day: Beta overpays B
     * by 200 on D2, and the payment is voided on D3.
     */
    @Test
    void aCustomersCreditIsAnsweredAsItWas() throws Exception {
        assertThat(read("/api/payments/credits/" + acme.getId(), D2).get("customerName").asText())
                .isEqualTo("Acme Ltd");
        assertThat(read("/api/payments/credits/" + acme.getId(), D3).get("customerName").asText())
                .isEqualTo("Zeta Holdings");
        call(get("/api/payments/credits/" + gamma.getId()), admin, D2).andExpect(status().isNotFound());

        on(D2);
        actAs(admin);
        Long overpaid = paymentService.record(new PaymentDtos.CreatePaymentRequest(beta.getId(),
                new BigDecimal("300.00"), "Cash", null, List.of(b), colin.getId(), null)).getId();
        on(D3);
        paymentService.voidPayment(overpaid);
        SecurityContextHolder.clearContext();
        on(TODAY);

        assertThat(read("/api/payments/credits/" + beta.getId(), D1).get("creditBalance").decimalValue())
                .isEqualByComparingTo("0");
        assertThat(read("/api/payments/credits/" + beta.getId(), D2).get("creditBalance").decimalValue())
                .isEqualByComparingTo("200.00");
        assertThat(read("/api/payments/credits/" + beta.getId(), D3).get("creditBalance").decimalValue())
                .isEqualByComparingTo("0");
        assertThat(read("/api/payments/credits/" + beta.getId(), null).get("creditBalance").decimalValue())
                .isEqualByComparingTo("0");
    }

    /** Payments, promises and disputes follow the region they were in on the day looked at, as customers do. */
    @Test
    void moneyRecordsAreSeenByThoseWhoHoldTheRegionTheyWereInThatDay() throws Exception {
        User nora = user("nora.north", "ADMIN");
        grantRepository.deleteAllOfUser(nora.getId());
        grant(nora, north, AccessLevel.APPROVE, RecordScope.ALL);

        call(get("/api/payments/" + paymentId), nora, D2).andExpect(status().isOk());
        call(get("/api/promises/" + promiseId), nora, D2).andExpect(status().isOk());
        call(get("/api/payments/" + paymentId), nora, D3).andExpect(status().isNotFound());
        call(get("/api/promises/" + promiseId), nora, D3).andExpect(status().isNotFound());
        call(get("/api/disputes/" + disputeId), nora, D4).andExpect(status().isNotFound());
        assertThat(body(call(get("/api/payments"), nora, D2).andExpect(status().isOk())).get("totalElements").asInt())
                .isEqualTo(1);
        assertThat(body(call(get("/api/payments"), nora, D3).andExpect(status().isOk())).get("totalElements").asInt())
                .isZero();

        // The customer's own login sees its records as they were; another customer's are missing.
        User acmeLogin = userRepository.findByUsername("acme.login").orElseThrow();
        assertThat(body(call(get("/api/payments/" + paymentId), acmeLogin, D2).andExpect(status().isOk()))
                .get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body(call(get("/api/disputes/" + disputeId), acmeLogin, D4).andExpect(status().isOk()))
                .get("resolvedByUserId").isNull()).as("a customer is not told who decided").isTrue();
        User betaLogin = customerUser("beta.login", beta.getId());
        call(get("/api/payments/" + paymentId), betaLogin, D2).andExpect(status().isNotFound());
        call(get("/api/promises/" + promiseId), betaLogin, D2).andExpect(status().isNotFound());
    }

    /**
     * The record an email tab belongs to is found as it was, product included (CF-23), so the tab's check that the
     * record is visible never reads today's row under a past date. (The tab's own list is answered at T in BE-10.)
     */
    @Test
    void theRecordBehindAnEmailTabIsFoundAsItWas() {
        actAs(admin);
        AsOfPoint d2 = AsOfPoint.endOfDay(D2);
        assertThat(AsOf.with(d2, () -> emailTargets.load(EmailEntityType.PRODUCT, widget.getId())).label())
                .isEqualTo("Product Widget");
        assertThat(AsOf.with(AsOfPoint.endOfDay(D3), () -> emailTargets.load(EmailEntityType.PRODUCT,
                widget.getId())).label()).isEqualTo("Product Widget Pro");
        assertThatThrownBy(() -> AsOf.with(d2, () -> {
            emailTargets.requireVisible(EmailEntityType.PRODUCT, gadget.getId());
            return null;
        })).isInstanceOf(NotFoundException.class).hasMessage("Product not found");
        for (EmailEntityType type : new EmailEntityType[] {EmailEntityType.CUSTOMER, EmailEntityType.INVOICE,
                EmailEntityType.PAYMENT, EmailEntityType.PROMISE}) {
            Long id = switch (type) {
                case CUSTOMER -> acme.getId();
                case INVOICE -> x;
                case PAYMENT -> paymentId;
                default -> promiseId;
            };
            AsOf.with(d2, () -> {
                emailTargets.requireVisible(type, id);
                return null;
            });
        }
        AsOf.with(AsOfPoint.endOfDay(D4), () -> {
            emailTargets.requireVisible(EmailEntityType.DISPUTE, disputeId);
            return null;
        });
        assertThatThrownBy(() -> AsOf.with(d2, () -> {
            emailTargets.requireVisible(EmailEntityType.DISPUTE, disputeId);
            return null;
        })).isInstanceOf(NotFoundException.class);
    }

    /** Today's grants, applied to the region the record was in on the day looked at (D9). */
    @Test
    void aRecordIsSeenByThoseWhoHoldTheRegionItWasInThatDay() throws Exception {
        User nora = user("nora.north", "ADMIN");
        grantRepository.deleteAllOfUser(nora.getId());
        grant(nora, north, AccessLevel.APPROVE, RecordScope.ALL);

        call(get("/api/customers/" + acme.getId()), nora, D2).andExpect(status().isOk());
        call(get("/api/invoices/" + x), nora, D2).andExpect(status().isOk());
        call(get("/api/customers/" + acme.getId()), nora, D3).andExpect(status().isNotFound());
        call(get("/api/invoices/" + x), nora, D3).andExpect(status().isNotFound());
        call(get("/api/customers/" + acme.getId()), nora, null).andExpect(status().isNotFound());

        assertThat(body(call(get("/api/customers"), nora, D2).andExpect(status().isOk())).get("totalElements").asInt())
                .isEqualTo(2);
        assertThat(body(call(get("/api/customers"), nora, D3).andExpect(status().isOk())).get("totalElements").asInt())
                .as("Beta and Gamma; Acme was in SOUTH by then").isEqualTo(2);
        assertThat(body(call(get("/api/invoices"), nora, D3).andExpect(status().isOk())).get("totalElements").asInt())
                .isEqualTo(1);
    }

    /** Sam sees only his own book: the invoices he sold, and the customers he sat on, on the day looked at. */
    @Test
    void aBookIsTheBookOfThatDay() throws Exception {
        call(get("/api/customers/" + acme.getId()), sam, D2).andExpect(status().isOk());
        JsonNode customersD2 = body(call(get("/api/customers"), sam, D2).andExpect(status().isOk()));
        assertThat(customersD2.get("totalElements").asInt()).as("Acme (his seat and invoices) and Beta (invoice)")
                .isEqualTo(2);
        JsonNode invoicesD2 = body(call(get("/api/invoices"), sam, D2).andExpect(status().isOk()));
        assertThat(invoicesD2.get("totalElements").asInt()).isEqualTo(3);
        assertThat(invoicesD2.get("bookRegionIds")).isNotEmpty();

        // Delta is in Sam's book by a seat alone, from D1 until it is taken from him on D3.
        on(D1);
        com.geneinvoice.customer.Customer delta = customer("Delta Co", north);
        actAs(admin);
        Long seat = pocService.add(delta.getId(), com.geneinvoice.poc.PocType.SUCCESS, sam.getId(), true).getId();
        on(D3);
        pocService.remove(delta.getId(), seat);
        on(TODAY);
        call(get("/api/customers/" + delta.getId()), sam, D2).andExpect(status().isOk());
        call(get("/api/customers/" + delta.getId()), sam, D3).andExpect(status().isNotFound());
        call(get("/api/customers/" + delta.getId()), sam, null).andExpect(status().isNotFound());
        assertThat(body(call(get("/api/customers"), sam, D2).andExpect(status().isOk())).get("totalElements").asInt())
                .isEqualTo(3);
    }

    /**
     * A customer whose history has a gap is not filled in with today's row: the invoice that points at it is refused
     * rather than naming the customer as it is now (§5.7.16). Only a product on a line falls back to live (§5.7.15).
     */
    @Test
    void aGapInHistoryIsRefusedRatherThanFilledWithToday() throws Exception {
        jdbc.update("delete from customer_versions where entity_id = ?", acme.getId());
        call(get("/api/invoices/" + x), admin, D2)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AS_OF_UNSUPPORTED"));
        call(get("/api/customers/" + acme.getId()), admin, D2).andExpect(status().isNotFound());
        call(get("/api/invoices/" + b), admin, D2).andExpect(status().isOk());

        jdbc.update("delete from product_versions where entity_id = ?", widget.getId());
        assertThat(read("/api/invoices/" + b, D2).at("/items/0/productName").asText())
                .as("a product with no version at the point is read live").isEqualTo("Widget Pro");

        // As the reconciler would: the missing versions, valid from now.
        historyTestSupport.touch(Customer.class, List.of(acme.getId()));
        historyTestSupport.touch(Product.class, List.of(widget.getId()));
    }

    /** Seats are listed in the same order live and as of a date: the primary first, then oldest. */
    @Test
    void seatsAreListedInOneOrderLiveAndAsOf() throws Exception {
        on(D4);
        actAs(admin);
        User sue = user("sue.success", "SALES_AND_SUCCESS");
        grant(sue, north, AccessLevel.READ, RecordScope.BOOK);
        Long first = pocService.add(beta.getId(), PocType.SUCCESS, sue.getId(), false).getId();
        Long primary = pocService.add(beta.getId(), PocType.SUCCESS, sam.getId(), true).getId();
        on(TODAY);

        for (JsonNode dto : new JsonNode[] {read("/api/customers/" + beta.getId(), null),
                read("/api/customers/" + beta.getId(), D4)}) {
            assertThat(dto.at("/successPocs/0/id").asLong()).isEqualTo(primary);
            assertThat(dto.at("/successPocs/1/id").asLong()).isEqualTo(first);
        }
    }
}
