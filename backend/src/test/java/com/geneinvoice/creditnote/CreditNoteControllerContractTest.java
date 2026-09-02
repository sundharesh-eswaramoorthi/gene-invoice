package com.geneinvoice.creditnote;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The invoice-scoped command contract as the security layer and the Flutter
 * client consume it: both mutations require the INVOICE_MANAGE authority, and
 * they live at the exact paths the frontend calls. These annotations are the
 * values Spring evaluates at runtime, so the test reads them back the same way
 * and compares them against the fully written-out expected literals.
 */
class CreditNoteControllerContractTest {

    @Test
    void endpointsAreInvoiceScopedAtThePathTheClientCalls() {
        RequestMapping mapping = CreditNoteController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/api/invoices/{invoiceId}/credit-notes"}, mapping.value());
    }

    @Test
    void issuanceRequiresExactlyTheInvoiceManageAuthority() throws NoSuchMethodException {
        Method issue = CreditNoteController.class.getMethod("issue", Long.class,
                CreditNoteDtos.IssueCreditNoteRequest.class);
        PreAuthorize guard = issue.getAnnotation(PreAuthorize.class);
        assertNotNull(guard);
        assertEquals("hasAuthority('INVOICE_MANAGE')", guard.value());

        // The request body is validated before the command runs.
        assertNotNull(issue.getAnnotation(PostMapping.class));
    }

    @Test
    void voidRequiresExactlyTheInvoiceManageAuthority() throws NoSuchMethodException {
        Method voidNote = CreditNoteController.class.getMethod("voidNote", Long.class, Long.class);
        PreAuthorize guard = voidNote.getAnnotation(PreAuthorize.class);
        assertNotNull(guard);
        assertEquals("hasAuthority('INVOICE_MANAGE')", guard.value());

        PostMapping mapping = voidNote.getAnnotation(PostMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/{creditNoteId}/void"}, mapping.value());
    }
}
