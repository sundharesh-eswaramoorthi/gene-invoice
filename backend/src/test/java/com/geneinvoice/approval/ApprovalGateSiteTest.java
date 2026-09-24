package com.geneinvoice.approval;

import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.common.BadRequestException;
import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2's completeness argument, written down as a build failure (B2).
 *
 * <p>Maker-checker in this application is an EXPLICIT call: a money mutator calls
 * {@code approvalGate.check(...)} itself, and nothing structural forces a mutator written in 2027
 * to do so. That angle was chosen over a Hibernate interceptor deliberately, and its price is
 * that coverage is an ENUMERATION rather than a property. An enumeration nobody checks is a
 * comment, so the enumeration is defended three ways: (i) {@code PendingChangeApplier.apply} is a
 * switch EXPRESSION over {@code PendingAction} with no {@code default}, so a fifteenth constant
 * without a branch does not compile; (ii) the review rule
 * {@code grep -rn "approvalGate.check(" backend/src/main/java} must return exactly one site per
 * constant; (iii) this class, which asserts (i) and (ii) hold and will not let either drift
 * quietly.
 *
 * <p>What this does NOT buy, said out loud because the design says it out loud: a FIFTEENTH money
 * mutator written in six months with no gate, no constant and no applier branch is invisible to
 * every assertion here. The two column-shaped tests at the bottom are the nearest thing to a
 * defence — they walk the money COLUMNS rather than the gate call sites, so a new writer of
 * {@code paidAmount} or {@code creditBalance} in an ungated method does fail the build — but a
 * brand-new money column on a brand-new table is only caught by its own census line. That is the
 * honest price of the explicit-call angle and it belongs in the release note, not in a green test
 * that pretends otherwise (B2).
 *
 * <p>Most of these assertions read the SOURCE TREE under {@code src/main/java} rather than the
 * running application, which is the same shape {@code RegionCoverageTest} uses for "only two
 * files build a CriteriaQuery": "there is exactly one gate call site per action" and "the ungated
 * neighbour says so" are statements about what is WRITTEN, and the change that must not pass
 * unnoticed is a second call site or a deleted comment, neither of which changes behaviour today.
 * It is still a full {@code @SpringBootTest} — the house rule is one kind of backend test — and
 * the metamodel it borrows from the context is what makes the money-column census complete rather
 * than a list somebody typed (B2).
 */
class ApprovalGateSiteTest extends IntegrationTestBase {

    @Autowired EntityManagerFactory entityManagerFactory;

    /** Maven's working directory for a test is the module directory, so this resolves (B2). */
    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path ROOT = MAIN.resolve("com").resolve("geneinvoice");

    private static final String GATE = "approvalGate.check(";

    /**
     * The literal string every ungated neighbour opens with. B2-GATE chose an OPENING marker
     * rather than the house's trailing {@code (B2)} tag for exactly one reason: a grep has to be
     * able to find the complement, and a trailing tag is indistinguishable from the hundred other
     * (B2) lines in these files (B2).
     */
    private static final String MARKER = "// not gated (B2)";

    /**
     * The six files allowed to hold a gate, so a gate that appears anywhere else is a deliberate
     * act that fails the build first and is argued about second. Four money services, the dispute
     * service (which holds ONE gate on the approval rather than four on the mutators it drives)
     * and B2's own threshold service (B2).
     */
    private static final Set<String> GATE_OWNERS = Set.of(
            "payment/PaymentService.java",
            "invoice/InvoiceService.java",
            "promise/PaymentPromiseService.java",
            "customer/CustomerService.java",
            "dispute/DisputeService.java",
            "approval/ApprovalThresholdService.java");

    /**
     * Every {@code // not gated (B2)} marker in the application, keyed file#method, with the one
     * sentence that says what the marker is for. Nine, not the design's five: B2-GATE added
     * sweepOverdue (which the design's own prose names beside clearOverride), B2-BULK added the
     * payments bulk arm (the first marker on a CONTROLLER rather than a service method), and this
     * unit added the product catalogue, which the design lists as a named hole and which nobody
     * had actually written down in the source (B2).
     *
     * <p>Both directions are asserted. A marker deleted fails here, and a marker added somewhere
     * new fails here too — which is the point: "this one moves no money" is a claim a reviewer
     * should have to read, not one that accumulates.
     */
    private static final Map<String, String> UNGATED = Map.ofEntries(
            Map.entry("invoice/InvoiceService.java#update",
                    "UpdateInvoiceRequest carries no monetary field"),
            Map.entry("payment/PaymentService.java#update",
                    "UpdatePaymentRequest is {notes, collectionPocUserId}"),
            Map.entry("customer/CustomerService.java#create",
                    "creditBalance appears on no request record"),
            Map.entry("customer/CustomerService.java#update",
                    "CustomerUpdateRequest carries no monetary field"),
            Map.entry("promise/PaymentPromiseService.java#clearOverride",
                    "the only hand-made figure is the one being removed, and putting it there was gated"),
            Map.entry("promise/PaymentPromiseService.java#recomputeAll",
                    "a NAMED HOLE: every customer in every region, so no single region and no threshold"),
            Map.entry("promise/PaymentPromiseService.java#sweepOverdue",
                    "no principal at all, and engine-derived statuses only"),
            Map.entry("payment/PaymentController.java#bulk",
                    "the one bulk arm whose single action moves no money"),
            Map.entry("product/ProductController.java#create",
                    "a NAMED HOLE: a catalogue price is copied onto the line, and there is no "
                            + "transaction here to roll back"));

    /**
     * The caller-supplied money columns: somebody types these into a request body, so each one is
     * measured by a gate at the mutator that writes it — except Product.price, which is the named
     * hole the last test in this class is about.
     *
     * <p>The design counts five and this counts four. InvoiceItem.quantity is the fifth and is
     * left out deliberately: it is an int rather than a {@code numeric(14,2)} column and is money
     * only once it multiplies a unit price, so it cannot take part in the census below. It is
     * written in exactly one place, {@code InvoiceService.buildLines}, which is reached only from
     * create and replaceItemsForDisputeApplication — gate sites 4 and 7 (B2).
     */
    private static final Set<String> CALLER_SUPPLIED = Set.of(
            "Payment.amount", "PaymentPromise.amount", "Product.price", "InvoiceItem.unitPrice");

    /**
     * The engine-computed money columns: nobody types these, they are DERIVED from what is on the
     * book. The design's list of six is seven in the real source — Invoice.total is derived from
     * the lines by {@code totalOf(items)} and belongs here beside lineTotal (B2).
     */
    private static final Set<String> ENGINE_COMPUTED = Set.of(
            "Invoice.total", "Invoice.paidAmount", "Payment.creditApplied",
            "PaymentAllocation.amount", "Customer.creditBalance",
            "PaymentPromise.fulfilledAmount", "InvoiceItem.lineTotal");

    /** B2's own bookkeeping. A limit and the two figures a held change was measured against are
     *  money-shaped columns that are not money on anybody's book (B2). */
    private static final Set<String> APPROVAL_OWN = Set.of(
            "ApprovalThreshold.amount", "PendingChange.exposure", "PendingChange.thresholdApplied");

    /**
     * B3's interval mirrors. Every one of these is a COPY of a column already classified above,
     * under the same attribute name — that identity is the whole of B3's storage design — and it
     * is written by one thing only: the history writer, copying a live row that a gated mutator
     * had already finished producing. Gating a mirror write would gate the RECORDING of a decision
     * that has already been taken, which is neither possible nor desirable, and the fourth bucket
     * says so rather than leaving eleven money columns unaccounted for (B2, B3).
     */
    private static final Set<String> MIRRORED = Set.of(
            "CustomerHistory.creditBalance",
            "InvoiceHistory.total", "InvoiceHistory.paidAmount",
            "InvoiceItemHistory.unitPrice", "InvoiceItemHistory.lineTotal",
            "PaymentHistory.amount", "PaymentHistory.creditApplied",
            "PaymentAllocationHistory.amount",
            "PromiseHistory.amount", "PromiseHistory.fulfilledAmount");

    /** The setters the review rule greps for, one per engine-computed column that has one. */
    private static final List<String> ENGINE_SETTERS = List.of(
            ".setPaidAmount(", ".setCreditApplied(", ".setCreditBalance(", ".setFulfilledAmount(");

    /**
     * The methods allowed to write an engine-computed money column WITHOUT being gated
     * themselves, each with the reason it is allowed to. Everything else that writes one must
     * hold an {@code approvalGate.check} of its own, and that half is computed from the source
     * rather than listed here, so a gate deleted from a writer fails this test as well as its
     * own (B2).
     */
    private static final Map<String, String> MONEY_ENGINES = Map.of(
            "payment/PaymentService.java#applyTo",
                    "private; spreads a payment over invoices and the rest into credit, always "
                            + "under record or updateAmount, both gated",
            "payment/PaymentService.java#reverseAllocations",
                    "private; un-applies a payment, always under voidPayment or updateAmount, "
                            + "both gated",
            "payment/CreditLedger.java#applyTo",
                    "internal engine; spends credit already on the book against an invoice",
            "payment/CreditLedger.java#refund",
                    "internal engine; pushes money off an invoice back into credit, always under "
                            + "a gated cancel or line replacement",
            "promise/PaymentPromiseService.java#applyShares",
                    "the promise engine; fulfilledAmount is shared out from payments that are "
                            + "already on the book and was never typed by anybody");

    // ---- the fourteen gate call sites -----------------------------------------------------------

    @Test
    void everyPendingActionHasExactlyOneGateCallSite() throws IOException {
        List<Site> gates = sitesOf(GATE);

        // The review rule, verbatim: grep -rn "approvalGate.check(" backend/src/main/java is
        // fourteen, one per constant. A fifteenth line here means either a constant grew a second
        // site (and two sites cannot both be "the first statement after the row is in hand") or
        // somebody gated something the enumeration does not know about (B2).
        assertThat(gates)
                .describedAs("one gate call site per PendingAction; found %s", describe(gates))
                .hasSize(PendingAction.values().length);

        // Each site names its action on the line below the call, so the two are read together.
        Map<PendingAction, List<Site>> byAction = new LinkedHashMap<>();
        for (PendingAction action : PendingAction.values()) {
            byAction.put(action, new ArrayList<>());
        }
        for (Site site : gates) {
            PendingAction action = actionNamedAt(site);
            assertThat(action)
                    .describedAs("the gate at %s names no PendingAction constant", site)
                    .isNotNull();
            byAction.get(action).add(site);
        }
        for (Map.Entry<PendingAction, List<Site>> entry : byAction.entrySet()) {
            assertThat(entry.getValue())
                    .describedAs("PendingAction.%s has %s gate call sites, expected exactly one",
                            entry.getKey(), entry.getValue().size())
                    .hasSize(1);
        }

        // And nowhere else in the application names a constant: a PendingAction mentioned away
        // from a gate is either a second decision point or dead reasoning, and both want reading.
        // The applier switches on unqualified case labels, so it is not one of these (B2).
        for (PendingAction action : PendingAction.values()) {
            // A word boundary, because INVOICE_CANCEL is a prefix of INVOICE_CANCEL_WITH_REFUND
            // and a plain substring search reports the shorter constant twice (B2).
            assertThat(linesMatching("PendingAction\\." + action.name() + "\\b"))
                    .describedAs("PendingAction.%s is named away from its gate", action)
                    .hasSize(1);
        }

        assertThat(sorted(gates.stream().map(Site::file)))
                .describedAs("a gate appeared in a file that does not own one")
                .isEqualTo(new TreeSet<>(GATE_OWNERS));
    }

    @Test
    void everyPendingActionHasAnApplierBranchAndTheSwitchHasNoDefault() throws IOException {
        String source = sourceOf("approval/PendingChangeApplier.java");

        // A switch EXPRESSION and not a statement: that is what makes a missing branch a compile
        // error rather than a change that can be raised and then silently does nothing (B2).
        assertThat(source).contains("switch (pc.getAction())");

        for (PendingAction action : PendingAction.values()) {
            Matcher matcher = Pattern.compile("\\bcase\\s+" + action.name() + "\\b").matcher(source);
            int branches = 0;
            while (matcher.find()) branches++;
            assertThat(branches)
                    .describedAs("PendingAction.%s has %s applier branches, expected exactly one",
                            action, branches)
                    .isEqualTo(1);
        }

        // THE ASSERTION THE COMPILER CANNOT MAKE. javac refuses a MISSING branch; it is perfectly
        // happy with a `default ->` that swallows the fifteenth constant into whichever arm a
        // well-meaning refactor put there. A default here would turn completeness-by-construction
        // back into completeness-by-hoping, silently, in a green build (B2).
        assertThat(source)
                .describedAs("PendingChangeApplier must have no default branch, or a new "
                        + "PendingAction compiles and is replayed as something else")
                .doesNotContain("default ->")
                .doesNotContain("default:");
    }

    // ---- the exception that must not be a 400 ---------------------------------------------------

    @Test
    void thePendingApprovalExceptionIsNotABadRequest() throws IOException {
        // LOAD-BEARING, and the reason is not style. BulkExecutor.eligibility wraps a record
        // operation and turns every BadRequestException into an IneligibleException, which the
        // bulk result reports as "skipped, did not qualify". If PendingApprovalException were a
        // BadRequestException, every held row of a bulk run would be reported with the one
        // sentence that says the opposite of what happened to it — and nothing would be parked,
        // because the gate's throw would never reach the arm that writes the change down. Do not
        // "tidy" this into the BadRequestException family (B2).
        assertThat(BadRequestException.class.isAssignableFrom(PendingApprovalException.class))
                .describedAs("PendingApprovalException must NOT extend BadRequestException; see "
                        + "BulkExecutor.eligibility")
                .isFalse();
        assertThat(PendingApprovalException.class.getSuperclass()).isEqualTo(RuntimeException.class);

        String eligibility = sourceOf("common/bulk/BulkExecutor.java");
        assertThat(eligibility)
                .describedAs("eligibility() converts BadRequestException and nothing else")
                .contains("catch (BadRequestException e)");

        // The other half of the same rule, and the half with no compile-time protection at all:
        // the catch that parks a held row has to sit ABOVE the generic one, or the generic one
        // takes it first and reports a failure. Nothing but this line stops a reorder (B2).
        int held = eligibility.indexOf("catch (PendingApprovalException");
        int generic = eligibility.indexOf("catch (RuntimeException");
        assertThat(held).describedAs("BulkExecutor no longer names PendingApprovalException")
                .isGreaterThan(0);
        assertThat(generic).isGreaterThan(0);
        assertThat(held)
                .describedAs("catch (PendingApprovalException) must stay above catch "
                        + "(RuntimeException) in BulkExecutor.run, or a held bulk row is reported "
                        + "as a failure and nothing is parked")
                .isLessThan(generic);
    }

    // ---- the complement --------------------------------------------------------------------------

    @Test
    void everyUngatedNeighbourOfAGatedMethodSaysSoInTheSource() throws IOException {
        List<Site> markers = sitesOf(MARKER);

        Map<String, String> found = new TreeMap<>();
        for (Site site : markers) {
            String key = site.key();
            assertThat(found)
                    .describedAs("two `not gated` markers on %s", key)
                    .doesNotContainKey(key);
            found.put(key, reasonAt(site));
        }

        assertThat(found.keySet())
                .describedAs("the set of methods that say they are deliberately ungated has moved")
                .isEqualTo(new TreeSet<>(UNGATED.keySet()));

        // A marker is only worth anything if it carries the WHY. "// not gated (B2)" on its own
        // is a shrug, and a shrug is how an ungated money mutator ships by accident — the same
        // rule RegionCoverageTest applies to an unregioned entity's reason (B2).
        for (Map.Entry<String, String> entry : found.entrySet()) {
            assertThat(entry.getValue())
                    .describedAs("%s is marked ungated with no reason a reviewer can disagree "
                            + "with; the note says: %s", entry.getKey(), UNGATED.get(entry.getKey()))
                    .hasSizeGreaterThan(60);
        }
    }

    // ---- the columns, which is the half that survives a fifteenth mutator -------------------------

    @Test
    void everyEngineComputedMonetaryColumnIsWrittenByOneOfFourMethods() throws IOException {
        // First the census itself, off the metamodel rather than off a list somebody typed: a
        // money column added in 2027 lands in neither bucket and fails here, which is the one
        // place in this class that notices a money column nobody told it about (B2).
        assertThat(moneyColumns())
                .describedAs("a money column is in none of the three buckets, or a bucket names "
                        + "a column that has gone")
                .isEqualTo(new TreeSet<>(Stream.of(CALLER_SUPPLIED, ENGINE_COMPUTED, APPROVAL_OWN,
                                MIRRORED)
                        .flatMap(Set::stream).toList()));

        // The fourth bucket is not a place to put a money column somebody did not want to think
        // about: a mirror column is only excused because it is the SAME column under the same
        // attribute name on a history table, so both halves of that claim are checked (B2, B3).
        Set<String> classifiedNames = Stream.of(CALLER_SUPPLIED, ENGINE_COMPUTED, APPROVAL_OWN)
                .flatMap(Set::stream).map(c -> c.substring(c.indexOf('.') + 1))
                .collect(Collectors.toCollection(TreeSet::new));
        for (String mirrored : MIRRORED) {
            assertThat(mirrored.substring(0, mirrored.indexOf('.')))
                    .describedAs("%s is excused as a mirror but is not one", mirrored)
                    .endsWith("History");
            assertThat(classifiedNames)
                    .describedAs("%s mirrors a money column nobody has classified", mirrored)
                    .contains(mirrored.substring(mirrored.indexOf('.') + 1));
        }

        Set<String> gatedMethods = new TreeSet<>();
        for (Site gate : sitesOf(GATE)) gatedMethods.add(gate.key());

        List<Site> writers = new ArrayList<>();
        for (String setter : ENGINE_SETTERS) writers.addAll(sitesOf(setter));
        assertThat(writers)
                .describedAs("the setter grep found nothing; the columns or their names moved")
                .isNotEmpty();

        Set<String> ungatedWriters = new TreeSet<>();
        for (Site writer : writers) {
            if (!gatedMethods.contains(writer.key())) ungatedWriters.add(writer.key());
        }

        // Every writer of an engine-computed column is either one of the engines named below or a
        // method that holds a gate of its own — and which is which is READ OFF THE SOURCE, not
        // declared here, so deleting a gate from InvoiceService.replaceItemsForDisputeApplication
        // fails this test as well as its own (B2).
        assertThat(ungatedWriters)
                .describedAs("a method that is neither gated nor a declared money engine writes "
                        + "an engine-computed money column; writers were %s", describe(writers))
                .isEqualTo(new TreeSet<>(MONEY_ENGINES.keySet()));

        // Not vacuous: the three gated writers are genuinely there and genuinely gated, so the
        // assertion above is not passing because the grep found only engines.
        assertThat(sorted(writers.stream().map(Site::key).filter(gatedMethods::contains)))
                .isEqualTo(new TreeSet<>(Set.of(
                        "invoice/InvoiceService.java#cancelWithRefundForDisputeApplication",
                        "invoice/InvoiceService.java#replaceItemsForDisputeApplication",
                        "promise/PaymentPromiseService.java#cancel")));
    }

    @Test
    void theOnlyUngatedMoneyPathsAreTheOnesWeNamed() throws IOException {
        // NAMED HOLE 1 — Product.price. A catalogue price is COPIED onto the line at
        // InvoiceService.buildLines, so moving it never re-prices an invoice that has been
        // issued, and the money it will eventually move is measured at INVOICE_CREATE. The second
        // reason is structural and is the one that would bite: ProductController has no service
        // and no @Transactional, so there is no transaction for the gate's throw to roll back.
        // Both are written at the call site, and removing either fails this test (B2).
        assertThat(sorted(sitesOf("in.price()").stream().map(Site::key)))
                .describedAs("Product.price is written outside ProductController; the named hole "
                        + "has grown a second door")
                .isEqualTo(new TreeSet<>(Set.of(
                        "product/ProductController.java#create",
                        "product/ProductController.java#update")));
        assertThat(sitesOf("setPrice(")).hasSize(1);
        assertReason("product/ProductController.java#create",
                "NAMED HOLE", "Product.price", "buildLines", "no service and no @Transactional");

        // NAMED HOLE 2 — POST /api/promises/recompute?apply=true. It spans every customer in
        // every region, so there is no single region behind it and therefore no threshold to
        // measure an exposure against. It stays behind the plain company-wide PROMISE_OVERRIDE
        // privilege, and the figures it moves are engine-derived from payments already on the
        // book (B2).
        assertReason("promise/PaymentPromiseService.java#recomputeAll",
                "NAMED HOLE", "no single region", "no threshold");

        // And the holes are the only two of their kind: every OTHER ungated marker in the
        // application is on a method that writes no money column at all, which is a weaker claim
        // than "named hole" and is exactly why those two are called out separately (B2).
        assertThat(sorted(UNGATED.keySet().stream().filter(k -> UNGATED.get(k).contains("NAMED HOLE"))))
                .isEqualTo(new TreeSet<>(Set.of(
                        "product/ProductController.java#create",
                        "promise/PaymentPromiseService.java#recomputeAll")));
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** A line of main source that matched, and the method it is written in. */
    private record Site(String file, String method, int line) {
        String key() {
            return file + "#" + method;
        }

        @Override
        public String toString() {
            return file + ":" + line + " (" + method + ")";
        }
    }

    /** A sorted set, built through a local so AssertJ's overloads cannot infer a Predicate. */
    private static Set<String> sorted(Stream<String> values) {
        return new TreeSet<>(values.toList());
    }

    private static String describe(List<Site> sites) {
        return sites.stream().map(Site::toString).sorted().toList().toString();
    }

    private void assertReason(String key, String... required) throws IOException {
        Site site = sitesOf(MARKER).stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no `" + MARKER + "` marker on " + key));
        String reason = reasonAt(site);
        for (String phrase : required) {
            assertThat(reason)
                    .describedAs("%s no longer says WHY it is ungated: '%s' has gone from the "
                            + "reason at %s", key, phrase, site)
                    .contains(phrase);
        }
    }

    /** Every {@code // ...} line from the marker down, as one line with its slashes stripped. */
    private static String reasonAt(Site site) throws IOException {
        List<String> lines = linesOf(site.file());
        StringBuilder reason = new StringBuilder();
        for (int i = site.line() - 1; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("//")) break;
            reason.append(' ').append(trimmed.substring(2).trim());
        }
        return reason.toString().replaceAll("\\s+", " ").trim();
    }

    /** The constant named by the gate call at this site, read off the call's own argument list. */
    private static PendingAction actionNamedAt(Site site) throws IOException {
        List<String> lines = linesOf(site.file());
        // Proposal.creating(...) and Proposal.on(...) both put the action first, on the next line
        // in every one of the fourteen sites; three lines of slack is for a reformat, not for a
        // second call (B2).
        for (int i = site.line() - 1; i < Math.min(lines.size(), site.line() + 3); i++) {
            Matcher matcher = Pattern.compile("PendingAction\\.([A-Z_]+)").matcher(lines.get(i));
            if (matcher.find()) return PendingAction.valueOf(matcher.group(1));
        }
        return null;
    }

    /** Every main-source line holding the needle, with the method it sits in. */
    private static List<Site> sitesOf(String needle) throws IOException {
        List<Site> found = new ArrayList<>();
        for (Path file : mainSources()) {
            String name = relative(file);
            List<String> lines = Files.readString(file).lines().toList();
            List<int[]> declarations = declarationsIn(lines);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains(needle)) continue;
                found.add(new Site(name, methodAround(lines, declarations, i), i + 1));
            }
        }
        return found;
    }

    private static List<String> linesMatching(String regex) throws IOException {
        Pattern pattern = Pattern.compile(regex);
        List<String> found = new ArrayList<>();
        for (Path file : mainSources()) {
            for (String line : Files.readString(file).lines().toList()) {
                if (pattern.matcher(line).find()) found.add(relative(file) + ": " + line.trim());
            }
        }
        return found;
    }

    /**
     * A member declaration at class level: exactly four spaces of indentation, a name and an open
     * bracket. Anything nested — a lambda, a loop body, an inner record's own members — is deeper
     * than that, so this finds methods and nothing else (B2).
     */
    private static final Pattern DECLARATION = Pattern.compile(
            "^ {4}(?! )(?:(?:public|private|protected|static|final|abstract|synchronized|default)\\s+)*"
                    + "[\\w.<>,\\[\\]?]+(?:<[^>]*>)?\\s+(\\w+)\\s*\\(");

    private static final Pattern TYPE_DECLARATION =
            Pattern.compile("\\b(record|class|enum|interface)\\s+\\w+");

    /** {line index, name index into the same list} for every method declaration in the file. */
    private static List<int[]> declarationsIn(List<String> lines) {
        List<int[]> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = DECLARATION.matcher(lines.get(i));
            if (matcher.find() && !TYPE_DECLARATION.matcher(lines.get(i)).find()) {
                found.add(new int[]{i, matcher.start(1), matcher.end(1)});
            }
        }
        return found;
    }

    /**
     * The method a line belongs to. A line inside a body belongs to the declaration above it; a
     * line in the GAP between two methods — which is where four of the nine ungated markers sit,
     * because a comment about a method reads better above its signature — belongs to the one
     * below. The gap is recognised by a closing brace at class-member indentation (B2).
     */
    private static String methodAround(List<String> lines, List<int[]> declarations, int index) {
        int[] previous = null;
        for (int[] declaration : declarations) {
            if (declaration[0] < index) previous = declaration;
        }
        if (previous != null) {
            boolean closed = false;
            for (int i = previous[0] + 1; i < index; i++) {
                if (lines.get(i).stripTrailing().equals("    }")) closed = true;
            }
            if (!closed) return name(lines, previous);
        }
        for (int[] declaration : declarations) {
            if (declaration[0] > index) return name(lines, declaration);
        }
        return "<none>";
    }

    private static String name(List<String> lines, int[] declaration) {
        return lines.get(declaration[0]).substring(declaration[1], declaration[2]);
    }

    /**
     * Every money column in the application, as Entity.field, off the running metamodel rather
     * than off a list somebody typed.
     *
     * <p>Verified against a real Postgres 16 as well as against H2: on the deployment engine
     * {@code select ... from information_schema.columns where data_type = 'numeric'} returns
     * these same fourteen columns and nothing else, so "precision 14 scale 2 is the money shape"
     * is a property of the generated DDL and not only of the annotations (B2).
     */
    private Set<String> moneyColumns() {
        Set<String> found = new TreeSet<>();
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            for (Field field : entity.getJavaType().getDeclaredFields()) {
                if (field.getType() != BigDecimal.class) continue;
                Column column = field.getAnnotation(Column.class);
                // precision 14 scale 2 is the house's money shape, declared on every one of them;
                // a BigDecimal without it is not a currency amount (B2).
                if (column == null || column.precision() != 14 || column.scale() != 2) continue;
                found.add(entity.getJavaType().getSimpleName() + "." + field.getName());
            }
        }
        assertThat(found).describedAs("no money column was found at all; the census is vacuous")
                .isNotEmpty();
        return found;
    }

    private static String sourceOf(String file) throws IOException {
        return String.join("\n", linesOf(file));
    }

    private static List<String> linesOf(String file) throws IOException {
        return Files.readString(ROOT.resolve(file)).lines().toList();
    }

    private static String relative(Path file) {
        return ROOT.relativize(file).toString().replace('\\', '/');
    }

    private static List<Path> mainSources() throws IOException {
        // Loudly, and with the path it actually looked at: a source-file test that silently finds
        // nothing passes every assertion in this class (B2).
        assertThat(Files.isDirectory(ROOT))
                .describedAs("run from the backend module directory; %s is not here",
                        ROOT.toAbsolutePath())
                .isTrue();
        try (Stream<Path> files = Files.walk(ROOT)) {
            List<Path> found = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            assertThat(found).hasSizeGreaterThan(100);
            return found;
        }
    }
}
