package com.geneinvoice.assignee;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.common.scope.ViewScope;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.product.Product;
import com.geneinvoice.region.AccessLevel;
import com.geneinvoice.region.RecordScope;
import com.geneinvoice.region.Region;
import com.geneinvoice.role.Role;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether someone else could open a record (§5.9.1): the record's VIEW privilege, a grant in its region, and the
 * record in their book where they only see a book. It never throws, so it can say "no" inside a write that goes on
 * to commit (CF-2).
 */
class VisibilityProbeTest extends IntegrationTestBase {

    @Autowired VisibilityProbe probe;
    @Autowired InvoiceService invoiceService;
    @Autowired TransactionTemplate tx;

    User admin;
    Region north;
    Region south;
    Customer acme;
    Invoice sidsInvoice;
    Invoice samsInvoice;
    User sid;
    User sam;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        north = region("NORTH");
        south = region("SOUTH");
        grant(admin, north, AccessLevel.APPROVE, RecordScope.ALL);
        sid = staff("sid.sales", DataSeeder.ROLE_SALES_POC, north, AccessLevel.READ, RecordScope.BOOK);
        sam = staff("sam.sales", DataSeeder.ROLE_SALES_POC, north, AccessLevel.READ, RecordScope.BOOK);
        Product widget = product("Widget", "100.00");
        acme = customer("Acme Ltd", north);
        actAs(admin);
        sidsInvoice = invoice(sid, widget);
        samsInvoice = invoice(sam, widget);
        SecurityContextHolder.clearContext();
    }

    private Invoice invoice(User seller, Product widget) {
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(acme.getId(), null, null, seller.getId(),
                List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    private User staff(String username, String role, Region region, AccessLevel level, RecordScope scope) {
        User u = user(username, role);
        grantRepository.deleteAllOfUser(u.getId());
        grant(u, region, level, scope);
        return u;
    }

    private boolean canSee(User u, Invoice i) {
        return probe.canSee(u.getId(), EmailEntityType.INVOICE, i.getId(), north.getId());
    }

    @Test
    void aFullGrantSeesEverythingInTheRegionAndABookOnlyItsOwn() {
        User cara = staff("cara.collections", DataSeeder.ROLE_COLLECTION_POC, north, AccessLevel.READ, RecordScope.ALL);
        assertThat(canSee(admin, sidsInvoice)).isTrue();
        assertThat(canSee(cara, sidsInvoice)).as("SCOPE_OVERRIDE with a grant of scope ALL: FULL").isTrue();
        assertThat(canSee(sid, sidsInvoice)).as("BOOK, in his book").isTrue();
        assertThat(canSee(sid, samsInvoice)).as("BOOK, someone else's").isFalse();
        assertThat(probe.canSee(sid.getId(), EmailEntityType.CUSTOMER, acme.getId(), north.getId()))
                .as("the customer of an invoice he sells is in his book").isTrue();
    }

    @Test
    void noGrantInTheRegionOrNoViewPrivilegeIsNo() {
        User stan = staff("stan.south", "CASHIER", south, AccessLevel.MANAGE, RecordScope.ALL);
        assertThat(canSee(stan, sidsInvoice)).as("NONE: nothing held in NORTH").isFalse();

        Role blind = roleRepository.save(Role.builder().name("NO_INVOICES").description("no invoices")
                .privileges(new HashSet<>(role("CASHIER").getPrivileges().stream()
                        .filter(p -> !p.getName().equals("INVOICE_VIEW")).toList())).build());
        User nina = staff("nina", blind.getName(), north, AccessLevel.APPROVE, RecordScope.ALL);
        assertThat(canSee(nina, sidsInvoice)).as("no INVOICE_VIEW").isFalse();
        assertThat(probe.canSee(nina.getId(), EmailEntityType.CUSTOMER, acme.getId(), north.getId())).isTrue();
    }

    @Test
    void someoneInactiveACustomerLoginOrNobodyIsNo() {
        User gone = staff("gone", "CASHIER", north, AccessLevel.APPROVE, RecordScope.ALL);
        gone.setActive(false);
        userRepository.save(gone);
        User login = customerUser("acme.login", acme.getId());

        assertThat(canSee(gone, sidsInvoice)).isFalse();
        assertThat(canSee(login, sidsInvoice)).isFalse();
        assertThat(probe.canSee(999999L, EmailEntityType.INVOICE, sidsInvoice.getId(), north.getId())).isFalse();
        assertThat(probe.canSee(null, EmailEntityType.INVOICE, sidsInvoice.getId(), north.getId())).isFalse();
        assertThat(probe.canSee(admin.getId(), EmailEntityType.PRODUCT, 1L, null)).as("no staff scope").isFalse();
    }

    /**
     * Asked inside a write transaction, a "no" leaves it able to commit (CF-2), and the caller's identity, regions
     * and provenance are theirs again afterwards.
     */
    @Test
    void aNoInsideAWriteLeavesTheWriteToCommitAndTheCallerAsTheyWere() {
        actAs(admin);
        Authentication mine = SecurityContextHolder.getContext().getAuthentication();
        ViewScopeHolder.set(new ViewScope(Set.of(north.getId()), null));

        Boolean answer = tx.execute(status -> {
            Customer c = customerRepository.findById(acme.getId()).orElseThrow();
            c.setName("Acme Holdings");
            customerRepository.save(c);
            boolean seen = canSee(sam, sidsInvoice);
            assertThat(status.isRollbackOnly()).isFalse();
            return seen;
        });

        assertThat(answer).isFalse();
        assertThat(customerRepository.findById(acme.getId()).orElseThrow().getName()).isEqualTo("Acme Holdings");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(mine);
        assertThat(ViewScopeHolder.current().regionIds()).containsExactly(north.getId());
        assertThat(WriteOrigins.current(null).kind().name()).isEqualTo("SYSTEM");
    }
}
