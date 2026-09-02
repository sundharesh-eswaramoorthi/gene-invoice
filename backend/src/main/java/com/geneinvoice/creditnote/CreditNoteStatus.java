package com.geneinvoice.creditnote;

/**
 * One-way lifecycle of a credit note: issued ACTIVE, may transition once to VOIDED.
 * Voided records are retained permanently and remain visible; only ACTIVE notes
 * count toward the invoice's active credited total.
 */
public enum CreditNoteStatus {
    ACTIVE,
    VOIDED
}
