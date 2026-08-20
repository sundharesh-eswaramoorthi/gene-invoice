package com.geneinvoice.invoice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.audit.AuditLog;
import com.geneinvoice.audit.AuditLogRepository;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level proof of the overdue-reminder tally on the invoice list.
 *
 * <p>The real {@link InvoiceService} and the real {@link AuditService} are wired
 * together over mocked repositories, so {@code listSummaries(Long)} and
 * {@code reminderCountsByInvoiceId(Collection)} are genuinely exercised. The one
 * mocked seam is {@link AuditLogRepository#countOverdueRemindersByEntityIds}: its
 * stubbed answer applies exactly the predicate the grouped JPQL query documents
 * (entityType {@code 'INVOICE'}, entityId among the supplied ids, action starting
 * with {@code OVERDUE_REMINDER_STEP_}, one row per raised reminder) over the
 * audit-row fixtures below. That keeps the tally assertions honest — non-matching
 * rows exist in the fixtures and must contribute nothing — without a database.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceListReminderCountTest {

    private static final String INVOICE_ENTITY_TYPE = "INVOICE";
    private static final String REMINDER_ACTION_PREFIX = "OVERDUE_REMINDER_STEP_";

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private CurrentUser currentUser;
    @Mock
    private AuditLogRepository auditLogRepository;

    private InvoiceService service;

    /** The audit history as the sweep and other features have written it. */
    private final List<AuditLog> auditRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AuditService auditService = new AuditService(auditLogRepository, new ObjectMapper());
        service = new InvoiceService(invoiceRepository, customerRepository,
                productRepository, currentUser, auditService);
    }

    private static Customer customer(Long id, String name) {
        return Customer.builder().id(id).name(name).build();
    }

    private static Invoice invoice(Long id, String number, Customer owner) {
        return Invoice.builder()
                .id(id).invoiceNumber(number).customer(owner)
                .invoiceDate(Instant.parse("2024-03-01T00:00:00Z"))
                .total(new BigDecimal("1000.00"))
                .paidAmount(BigDecimal.ZERO)
                .status(InvoiceStatus.UNPAID)
                .build();
    }

    private static AuditLog auditRow(String entityType, Long entityId, String action) {
        return AuditLog.builder().entityType(entityType).entityId(entityId).action(action).build();
    }

    private static AuditLogRepository.ReminderCountProjection projection(Long entityId, long reminderCount) {
        return new AuditLogRepository.ReminderCountProjection() {
            @Override
            public Long getEntityId() {
                return entityId;
            }

            @Override
            public long getReminderCount() {
                return reminderCount;
            }
        };
    }

    /**
     * Stub the batched grouped query so that it behaves exactly like its documented
     * predicate over the audit-row fixtures: one projected row per supplied invoice
     * id that has INVOICE-entity audit rows whose action starts with
     * {@code OVERDUE_REMINDER_STEP_}.
     */
    private void stubGroupedReminderCountQuery() {
        when(auditLogRepository.countOverdueRemindersByEntityIds(anyCollection()))
                .thenAnswer(invocation -> {
                    Collection<Long> ids = new HashSet<>(invocation.getArgument(0));
                    Map<Long, Long> counts = new LinkedHashMap<>();
                    for (AuditLog row : auditRows) {
                        if (INVOICE_ENTITY_TYPE.equals(row.getEntityType())
                                && ids.contains(row.getEntityId())
                                && row.getAction() != null
                                && row.getAction().startsWith(REMINDER_ACTION_PREFIX)) {
                            counts.merge(row.getEntityId(), 1L, Long::sum);
                        }
                    }
                    return counts.entrySet().stream()
                            .map(e -> projection(e.getKey(), e.getValue()))
                            .toList();
                });
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<Long>> invoiceIdsCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    @Test
    void fullListTalliesReminderStepRowsPerInvoiceAndIgnoresOtherAuditRows() {
        Customer acme = customer(1L, "Acme");
        Invoice withReminders = invoice(11L, "INV-20240301-0001", acme);
        Invoice withoutReminders = invoice(12L, "INV-20240301-0002", acme);
        // Two raised reminder steps on invoice 11.
        auditRows.add(auditRow(INVOICE_ENTITY_TYPE, 11L, "OVERDUE_REMINDER_STEP_1"));
        auditRows.add(auditRow(INVOICE_ENTITY_TYPE, 11L, "OVERDUE_REMINDER_STEP_2"));
        // Non-matching history on the same invoice: none of this may be counted.
        auditRows.add(auditRow(INVOICE_ENTITY_TYPE, 11L, "DISPUTE_APPROVED"));
        auditRows.add(auditRow(INVOICE_ENTITY_TYPE, 11L, "INVOICE_CREATED"));
        // A reminder-shaped action recorded under another entity type must not count either.
        auditRows.add(auditRow("PAYMENT", 11L, "OVERDUE_REMINDER_STEP_1"));
        when(currentUser.customerIdOrNull()).thenReturn(null);
        when(invoiceRepository.findAll()).thenReturn(List.of(withReminders, withoutReminders));
        stubGroupedReminderCountQuery();

        List<InvoiceDtos.InvoiceSummary> summaries = service.listSummaries(null);

        assertEquals(2, summaries.size());
        assertEquals(11L, summaries.get(0).id().longValue());
        assertEquals(2L, summaries.get(0).reminderCount());
        assertEquals(12L, summaries.get(1).id().longValue());
        // reminderCount is a primitive long: present on every summary, 0 when
        // nothing matches, and therefore never null.
        assertEquals(0L, summaries.get(1).reminderCount());
    }

    @Test
    void fullListFetchesCountsInOneBatchedQueryOfExactlyTheListedInvoiceIds() {
        Customer acme = customer(1L, "Acme");
        Invoice inv1 = invoice(11L, "INV-20240301-0001", acme);
        Invoice inv2 = invoice(12L, "INV-20240301-0002", acme);
        when(currentUser.customerIdOrNull()).thenReturn(null);
        when(invoiceRepository.findAll()).thenReturn(List.of(inv1, inv2));
        stubGroupedReminderCountQuery();

        service.listSummaries(null);

        ArgumentCaptor<Collection<Long>> ids = invoiceIdsCaptor();
        verify(auditLogRepository, times(1)).countOverdueRemindersByEntityIds(ids.capture());
        assertEquals(Set.of(11L, 12L), new HashSet<>(ids.getValue()));
        // The tally must never be computed by loading each invoice's audit history.
        verify(auditLogRepository, never()).findByEntityTypeAndEntityIdOrderByCreatedAtDesc(anyString(), anyLong());
    }

    @Test
    void customerFilteredListCarriesReminderCountOnEveryInvoiceAndQueriesOnlyThoseIds() {
        Customer acme = customer(1L, "Acme");
        Invoice inv3 = invoice(13L, "INV-20240301-0003", acme);
        Invoice inv4 = invoice(14L, "INV-20240301-0004", acme);
        auditRows.add(auditRow(INVOICE_ENTITY_TYPE, 13L, "OVERDUE_REMINDER_STEP_1"));
        auditRows.add(auditRow(INVOICE_ENTITY_TYPE, 14L, "DISPUTE_APPROVED"));
        when(currentUser.customerIdOrNull()).thenReturn(null);
        when(invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(1L)).thenReturn(List.of(inv3, inv4));
        stubGroupedReminderCountQuery();

        List<InvoiceDtos.InvoiceSummary> summaries = service.listSummaries(1L);

        assertEquals(2, summaries.size());
        assertEquals(1L, summaries.get(0).reminderCount());
        // DISPUTE_APPROVED is not a reminder row, so the second invoice defaults to 0, never null.
        assertEquals(0L, summaries.get(1).reminderCount());

        ArgumentCaptor<Collection<Long>> ids = invoiceIdsCaptor();
        verify(auditLogRepository, times(1)).countOverdueRemindersByEntityIds(ids.capture());
        assertEquals(Set.of(13L, 14L), new HashSet<>(ids.getValue()));
        verify(auditLogRepository, never()).findByEntityTypeAndEntityIdOrderByCreatedAtDesc(anyString(), anyLong());
    }

    @Test
    void customerRestrictedCallerGetsCountsComputedForOwnInvoicesOnly() {
        Customer own = customer(5L, "Caller Ltd");
        Customer other = customer(6L, "Other Ltd");
        Invoice ownInvoice = invoice(15L, "INV-20240301-0005", own);
        Invoice othersInvoice = invoice(16L, "INV-20240301-0006", other);
        auditRows.add(auditRow(INVOICE_ENTITY_TYPE, 15L, "OVERDUE_REMINDER_STEP_3"));
        auditRows.add(auditRow(INVOICE_ENTITY_TYPE, 16L, "OVERDUE_REMINDER_STEP_1"));
        auditRows.add(auditRow(INVOICE_ENTITY_TYPE, 16L, "OVERDUE_REMINDER_STEP_2"));
        // The caller belongs to customer 5; the existing restriction means the
        // repository only ever returns that customer's invoices.
        when(currentUser.customerIdOrNull()).thenReturn(5L);
        when(invoiceRepository.findByCustomerIdOrderByInvoiceDateDesc(5L)).thenReturn(List.of(ownInvoice));
        stubGroupedReminderCountQuery();

        List<InvoiceDtos.InvoiceSummary> summaries = service.listSummaries(null);

        assertEquals(1, summaries.size());
        assertEquals(15L, summaries.get(0).id().longValue());
        assertEquals(5L, summaries.get(0).customerId().longValue());
        assertEquals(1L, summaries.get(0).reminderCount());

        // The count query received only the caller's already-authorized invoice id:
        // invoice 16 never reaches it, so the other customer's tally of 2 is
        // unreachable from this list.
        ArgumentCaptor<Collection<Long>> ids = invoiceIdsCaptor();
        verify(auditLogRepository, times(1)).countOverdueRemindersByEntityIds(ids.capture());
        assertEquals(Set.of(15L), new HashSet<>(ids.getValue()));
        verify(invoiceRepository, never()).findAll();
    }

    @Test
    void customerRestrictedCallerAskingForAnotherCustomersListIsDeniedBeforeAnyCountQuery() {
        when(currentUser.customerIdOrNull()).thenReturn(5L);

        assertThrows(AccessDeniedException.class, () -> service.listSummaries(6L));

        verify(auditLogRepository, never()).countOverdueRemindersByEntityIds(anyCollection());
    }

    @Test
    void emptyListYieldsNoSummariesAndRunsNoCountQuery() {
        when(currentUser.customerIdOrNull()).thenReturn(null);
        when(invoiceRepository.findAll()).thenReturn(List.of());

        assertTrue(service.listSummaries(null).isEmpty());

        verify(auditLogRepository, never()).countOverdueRemindersByEntityIds(anyCollection());
    }
}
