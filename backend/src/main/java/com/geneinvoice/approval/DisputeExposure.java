package com.geneinvoice.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * What a dispute approval would actually move, measured before it moves (B2).
 *
 * <p>A dispute is the one write in the application whose amount is not in the request: the request
 * carries a blob of JSON naming an action, and the money is in the RECORD that action would change.
 * The vocabulary read here is exactly the vocabulary {@code DisputeService.applyChange} already
 * reads, and each branch returns the same figure the inner mutator's own gate would have measured,
 * so a dispute approval is scored as the thing it is about to do rather than as a separate act:
 * {@code cancel} matches InvoiceService.cancelWithRefundForDisputeApplication's
 * {@code total.max(paidAmount)} and {@code replace_items} matches replaceItemsForDisputeApplication's
 * {@code total.max(newTotal)} line for line (B2).
 *
 * <p>NOTHING here ever throws. A dispute whose change is malformed, whose target has gone or whose
 * action nobody recognises is worth ZERO and goes straight to applyChange, which refuses it with
 * its own message: the gate is not the place a malformed dispute is reported, and a measuring
 * function that threw would turn a 400 about JSON into a 500 about approvals (B2).
 *
 * <p>MEASURING ALSO FREEZES, which is why there is one entry point and it hands back BOTH halves:
 * see {@link #score}.
 */
@Component
@RequiredArgsConstructor
public class DisputeExposure {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;

    /**
     * The change as it will be APPLIED, and what it is worth — one measurement of one text.
     *
     * <p>{@code changeJson} is the maker's own text byte for byte unless a price had to be frozen
     * into it, because a change nobody had to touch should read in the queue exactly as it was
     * written (B2).
     */
    public record Scored(String changeJson, BigDecimal exposure) {}

    /**
     * THE AMOUNT APPROVED IS THE AMOUNT APPLIED, for a dispute as much as for an invoice (B2).
     *
     * <p>A dispute's {@code replace_items} lines are priced by InvoiceService.buildLines, which
     * falls back to the live catalogue price when a line names none — so a measurement taken now
     * and a replay run next week are two different amounts, and the DISPUTE_APPROVE payload is the
     * dispute's OWN proposedChangeJson rather than the payload InvoiceService.frozen writes, so the
     * invoice-side freeze does not reach it. The same copy is therefore made HERE, into the text
     * that is then measured, parked and applied, and this method hands back both halves together
     * so that no caller can measure one text and park another (B2).
     *
     * <p>Read-only transactional: the branches below walk Invoice, Payment and Product, and
     * open-in-view is off, so a caller outside a transaction would otherwise meet a lazy-loading
     * failure instead of a number (B2).
     */
    @Transactional(readOnly = true)
    public Scored score(Dispute d, String changeJson) {
        JsonNode node = parse(changeJson);
        if (node == null) return new Scored(changeJson, BigDecimal.ZERO);
        String action = node.path("action").asText("");
        // Freeze FIRST, then measure the frozen text: measuring the maker's text and parking a
        // different one is the whole defect this closes (B2).
        boolean froze = freezePrices(d, action, node);
        BigDecimal exposure = switch (d.getTargetType()) {
            case INVOICE -> ofInvoice(d.getTargetId(), action, node);
            case PAYMENT -> ofPayment(d.getTargetId(), action, node);
        };
        return new Scored(froze ? node.toString() : changeJson, exposure);
    }

    /**
     * Copies today's catalogue price onto every proposed line that names none, in place, and says
     * whether it wrote anything. The mirror of InvoiceService.frozen: a product withdrawn while
     * the change waits is still refused by buildLines on replay, because only the PRICE is copied
     * and the product is still looked up (B2).
     */
    private boolean freezePrices(Dispute d, String action, JsonNode node) {
        if (d.getTargetType() != DisputeTargetType.INVOICE || !"replace_items".equals(action)) {
            return false;
        }
        JsonNode items = node.path("items");
        if (!items.isArray()) return false;
        boolean froze = false;
        for (JsonNode line : items) {
            if (!(line instanceof ObjectNode proposed)) continue;
            JsonNode named = proposed.get("unitPrice");
            // A price that is PRESENT but unreadable is not a missing price: DisputeService.decimal
            // throws on it and applyChange refuses the whole change by name. Writing a catalogue
            // price over it would turn a 400 into money moving (B2).
            if (named != null && !named.isNull()) continue;
            Long productId = wholeNumber(proposed.get("productId"));
            Product p = productId == null ? null
                    : productRepository.findById(productId).orElse(null);
            // Nothing to copy, and nothing to report: applyChange refuses this line by name a
            // moment later, exactly as it does today (B2).
            if (p == null) continue;
            proposed.put("unitPrice", p.getPrice());
            froze = true;
        }
        return froze;
    }

    private JsonNode parse(String changeJson) {
        if (changeJson == null || changeJson.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(changeJson);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal ofInvoice(Long invoiceId, String action, JsonNode node) {
        Invoice inv = invoiceRepository.findById(invoiceId).orElse(null);
        if (inv == null) return BigDecimal.ZERO;
        return switch (action) {
            // Cancelling with a refund both takes the whole invoice off the book and pushes
            // everything paid on it back into credit; the larger of the two is what somebody is
            // being asked to agree to, and it is the figure the inner gate uses (B2).
            case "cancel" -> inv.getTotal().max(inv.getPaidAmount());
            // A replacement is a full reversal followed by a full re-application, so it is scored
            // at its larger side rather than at the difference between them (B2).
            case "replace_items" -> inv.getTotal().max(proposedTotal(node));
            // Notes are not money. Named rather than left to the default, so the absence is a
            // decision somebody wrote down (B2).
            case "update_notes" -> BigDecimal.ZERO;
            default -> BigDecimal.ZERO;
        };
    }

    private BigDecimal ofPayment(Long paymentId, String action, JsonNode node) {
        Payment p = paymentRepository.findById(paymentId).orElse(null);
        if (p == null) return BigDecimal.ZERO;
        return switch (action) {
            // The request carries no amount at all: what leaves the book is the whole payment (B2).
            case "void" -> p.getAmount();
            // max(before, after) and not the difference: updateAmount un-applies the entire old
            // amount and re-applies the new one across every invoice of the customer, so a delta
            // rule would score a fifty-lakh reversal and a fifty-lakh re-application as nothing (B2).
            case "update_amount" -> p.getAmount().max(amountOf(node));
            case "update_meta" -> BigDecimal.ZERO;
            default -> BigDecimal.ZERO;
        };
    }

    /**
     * What the proposed lines would come to, priced the way InvoiceService.buildLines prices them:
     * the line's own unit price when it names one, and the catalogue price when it does not. By the
     * time this runs freezePrices has already written that catalogue price INTO the line, so the
     * fallback here is only ever reached for a line the freeze could not read — and such a line is
     * one applyChange is about to refuse by name, so the measurement never reaches a checker (B2).
     * A line this cannot read contributes nothing rather than taking the measurement down (B2).
     */
    private BigDecimal proposedTotal(JsonNode node) {
        JsonNode items = node.path("items");
        if (!items.isArray()) return BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode line : items) {
            BigDecimal unitPrice = decimal(line.get("unitPrice"));
            if (unitPrice == null) {
                Long productId = wholeNumber(line.get("productId"));
                Product p = productId == null ? null
                        : productRepository.findById(productId).orElse(null);
                if (p == null) continue;
                unitPrice = p.getPrice();
            }
            Long quantity = wholeNumber(line.get("quantity"));
            if (quantity == null) continue;
            total = total.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
        }
        return total;
    }

    private static BigDecimal amountOf(JsonNode node) {
        BigDecimal amount = decimal(node.get("amount"));
        return amount == null ? BigDecimal.ZERO : amount;
    }

    // The DisputeService.decimal/wholeNumber vocabulary, reading the same two shapes a proposed
    // change is written in, but answering null where those throw (B2).
    private static BigDecimal decimal(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        if (v.isTextual()) {
            try {
                return new BigDecimal(v.asText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Long wholeNumber(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isIntegralNumber() && v.canConvertToLong()) return v.asLong();
        if (v.isTextual()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
