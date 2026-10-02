package com.geneinvoice.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geneinvoice.MovableClock;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.CustomerDtos;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.product.Product;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.common.scope.ViewScopeInterceptor;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Yesterday as of yesterday is yesterday (§8.4, CF-13): every answer captured live at the end of the
 * {@link AsOfScenario} story is asked for again the next day as of that day, and must come back the same, apart
 * from what differs by definition: {@code asOf}, row {@code version}s, the header, and the capability flags, which
 * must instead be off (D14). The list requests are generated from each {@link TableSchema}: every filterable
 * column with one valid operator for its type, and every sortable column, so a column added later joins the check.
 */
class AsOfParityTest extends AsOfScenario {

    /** The list endpoints answered from versions, and the schema each is built on. */
    static final Map<String, TableSchema> LISTS = new LinkedHashMap<>();

    static {
        LISTS.put("/api/customers", TableSchemas.CUSTOMERS);
        LISTS.put("/api/invoices", TableSchemas.INVOICES);
        LISTS.put("/api/payments", TableSchemas.PAYMENTS);
        LISTS.put("/api/promises", TableSchemas.PROMISES);
        LISTS.put("/api/disputes", TableSchemas.DISPUTES);
        LISTS.put("/api/products", TableSchemas.PRODUCTS);
    }

    static final List<String> SUMMARIES = List.of("/api/customers/summary", "/api/invoices/summary",
            "/api/payments/summary", "/api/promises/summary");

    static final List<String> DASHBOARD = List.of("/api/dashboard/billed-by-month",
            "/api/dashboard/outstanding-by-age", "/api/dashboard/top-outstanding-customers",
            "/api/dashboard/collected-by-month", "/api/dashboard/top-paying-customers");

    static final Pattern FLAG = Pattern.compile("can[A-Z]\\w*|editTakesOwnership|blockedReason");

    /** One request: a path and its query parameters. */
    record Ask(String path, MultiValueMap<String, String> params) {
        static Ask of(String path, String... params) {
            MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
            for (int i = 0; i < params.length; i += 2) p.add(params[i], params[i + 1]);
            return new Ask(path, p);
        }

        @Override
        public String toString() {
            return path + (params.isEmpty() ? "" : " " + params);
        }
    }

    /**
     * A filter on {@code c} that some live row passes: the column's value in the first of {@code rows} that has one,
     * so that a predicate which loses rows as of a date shows up as a difference, not as an empty page compared with
     * an empty page. A column the rows do not carry gets one valid filter for its type.
     */
    static String filterFor(ColumnDef c, JsonNode rows) {
        for (JsonNode row : rows) {
            JsonNode v = row.get(c.name());
            if (v == null || v.isNull() || v.isContainerNode() || v.asText().isBlank()) continue;
            String s = v.asText();
            return c.name() + ":" + switch (c.type()) {
                case TEXT -> "contains:" + s.split(",")[0];
                case ENUM, BOOLEAN, NUMBER, MONEY, REFERENCE -> "eq:" + s;
                case DATE -> "between:" + s.substring(0, 10) + "," + s.substring(0, 10);
            };
        }
        return c.name() + ":" + switch (c.type()) {
            case TEXT, REFERENCE -> "isNotEmpty:";
            case ENUM -> "neq:" + c.enumValues().get(0);
            case BOOLEAN -> "eq:true";
            case NUMBER -> "gte:1";
            case MONEY -> "gt:0";
            case DATE -> "relative:last30Days";
        };
    }

    private static Ask base(String path) {
        return Ask.of(path, "size", "50");
    }

    /**
     * Every list × every filterable column (valued from the live rows in {@code bases}), every sortable column, the
     * summaries, the dashboard (D1–D8).
     */
    private List<Ask> asks(Map<String, String> bases) throws Exception {
        List<Ask> asks = new ArrayList<>();
        for (Map.Entry<String, TableSchema> list : LISTS.entrySet()) {
            String path = list.getKey();
            JsonNode rows = objectMapper.readTree(bases.get(base(path).toString())).get("content");
            asks.add(base(path));
            for (ColumnDef c : list.getValue().columns()) {
                if (c.filterable()) asks.add(Ask.of(path, "size", "50", "filter", filterFor(c, rows)));
                if (c.sortable()) asks.add(Ask.of(path, "size", "50", "sort", c.name() + ",desc"));
            }
        }
        SUMMARIES.forEach(s -> asks.add(Ask.of(s)));
        DASHBOARD.forEach(d -> asks.add(Ask.of(d)));
        asks.add(Ask.of("/api/promises", "size", "10", "sort", "promisedDate,asc",
                "filter", "status:in:OPEN,PARTIALLY_KEPT", "filter", "promisedDate:gte:" + TODAY));
        return asks;
    }

    /** Every record's detail, found from the live lists, and each customer's credit and POC tab. */
    private List<Ask> details(Map<String, String> live) throws Exception {
        List<Ask> asks = new ArrayList<>();
        for (String path : LISTS.keySet()) {
            JsonNode page = objectMapper.readTree(live.get(base(path).toString()));
            for (JsonNode row : page.get("content")) {
                String id = row.get("id").asText();
                asks.add(Ask.of(path + "/" + id));
                if (path.equals("/api/customers")) {
                    asks.add(Ask.of("/api/payments/credits/" + id));
                    asks.add(Ask.of("/api/customers/" + id + "/pocs"));
                }
            }
        }
        return asks;
    }

    /**
     * The rest of the day, so that every column has a row that answers its filter: Gamma's contact details and
     * Collection seat, a Success seat on Beta, Gadget's description, and for Gamma an invoice, a promise and a payment
     * that keeps it, each with notes; the promise and Beta's dispute each with someone assigned.
     */
    private void aRowForEveryColumn(Long disputeId) {
        actAs(admin);
        customerService.update(gamma.getId(), new CustomerDtos.CustomerUpdateRequest("Gamma Co", "0113 496 0000",
                "accounts@gamma.example", "1 Park Row, Leeds", null, null));
        pocService.add(beta.getId(), PocType.SUCCESS, sam.getId(), true);
        pocService.add(gamma.getId(), PocType.COLLECTION, colin.getId(), true);
        Product described = productRepository.findById(gadget.getId()).orElseThrow();
        described.setDescription("A small gadget");
        productRepository.save(described);
        Long invoice = invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(gamma.getId(), null,
                TODAY.plusDays(30), null, "Quarterly order", sam.getId(),
                List.of(new InvoiceDtos.LineInput(gadget.getId(), 5, null)))).getId();
        Long promise = promiseService.create(new PromiseDtos.CreatePromiseRequest(gamma.getId(),
                new BigDecimal("60.00"), TODAY.plusDays(3), colin.getId(), "Will pay on Friday",
                List.of(invoice))).id();
        paymentService.record(new PaymentDtos.CreatePaymentRequest(gamma.getId(), new BigDecimal("60.00"), "Bank",
                "First instalment", List.of(invoice), colin.getId(), List.of(promise)));
        promiseService.setAssignees(promise, List.of(EmailDtos.EmailToken.user(admin.getId())));
        disputeService.setAssignees(disputeId, List.of(EmailDtos.EmailToken.user(admin.getId())));
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse send(Ask ask, User caller, boolean historical) throws Exception {
        var request = get(ask.path()).params(ask.params());
        return call(request, caller, historical ? TODAY : null).andReturn().getResponse();
    }

    @Test
    void theDayAsOfThatDayIsWhatWasShownThatDay() throws Exception {
        aRowForEveryColumn(betaDisputesB());
        User acmeLogin = userRepository.findByUsername("acme.login").orElseThrow();

        List<Ask> asks = asks(capture(LISTS.keySet().stream().map(AsOfParityTest::base).toList(), admin));
        asks.addAll(details(capture(asks, admin)));
        Map<String, String> adminLive = capture(asks, admin);
        List<String> emptyLive = new ArrayList<>();
        for (Ask ask : asks) {
            if (LISTS.containsKey(ask.path()) && ask.params().containsKey("filter")
                    && objectMapper.readTree(adminLive.get(ask.toString())).get("content").isEmpty()) {
                emptyLive.add(ask.toString());
            }
        }
        List<Ask> theirs = new ArrayList<>();
        LISTS.keySet().stream().filter(p -> !p.equals("/api/products")).forEach(p -> theirs.add(base(p)));
        theirs.addAll(asks.stream().filter(a -> a.path().contains("/summary") || a.path().contains("/dashboard/"))
                .filter(a -> !a.path().contains("top-")).toList());
        Map<String, String> samLive = capture(theirs, sam);
        Map<String, String> acmeLive = capture(theirs, acmeLogin);

        MovableClock.SHARED.set(TODAY.plusDays(1).atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC));

        List<String> problems = new ArrayList<>();
        compare(asks, admin, adminLive, problems);
        compare(theirs, sam, samLive, problems);
        compare(theirs, acmeLogin, acmeLive, problems);
        assertThat(asks).as("the matrix was generated").hasSizeGreaterThan(100);
        assertThat(emptyLive).as("every column filter keeps a row live, so losing it as of a date shows").isEmpty();
        assertThat(problems).isEmpty();
    }

    private Map<String, String> capture(List<Ask> asks, User caller) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (Ask ask : asks) {
            MockHttpServletResponse response = send(ask, caller, false);
            assertThat(response.getStatus()).as(caller.getUsername() + " " + ask).isEqualTo(200);
            out.put(ask.toString(), response.getContentAsString(StandardCharsets.UTF_8));
        }
        return out;
    }

    private void compare(List<Ask> asks, User caller, Map<String, String> live, List<String> problems)
            throws Exception {
        for (Ask ask : asks) {
            String who = caller.getUsername() + " " + ask;
            MockHttpServletResponse response = send(ask, caller, true);
            if (response.getStatus() != 200) {
                problems.add(who + ": " + response.getStatus() + " " + response.getContentAsString());
                continue;
            }
            if (!TODAY.toString().equals(response.getHeader(ViewScopeInterceptor.HEADER))) {
                problems.add(who + ": header " + response.getHeader(ViewScopeInterceptor.HEADER));
            }
            JsonNode then = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            List<String> flagsOn = new ArrayList<>();
            flagsOn(then, "$", flagsOn);
            if (!flagsOn.isEmpty()) problems.add(who + ": flags on as of a date " + flagsOn);
            JsonNode now = objectMapper.readTree(live.get(ask.toString()));
            strip(then);
            strip(now);
            if (!then.equals(now)) problems.add(who + ":\n  live " + now + "\n  then " + then);
        }
    }

    /** Capability flags that are set in a historical answer, where every one must be off. */
    private static void flagsOn(JsonNode node, String at, List<String> out) {
        if (node instanceof ObjectNode obj) {
            obj.fields().forEachRemaining(f -> {
                if (FLAG.matcher(f.getKey()).matches() && !f.getValue().isNull()
                        && !(f.getValue().isBoolean() && !f.getValue().asBoolean())) {
                    out.add(at + "." + f.getKey() + "=" + f.getValue());
                }
                flagsOn(f.getValue(), at + "." + f.getKey(), out);
            });
        } else if (node != null && node.isArray()) {
            for (int i = 0; i < node.size(); i++) flagsOn(node.get(i), at + "[" + i + "]", out);
        }
    }

    /** Takes out what differs by definition: the date, row versions and the capability flags. */
    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            for (Iterator<String> names = obj.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (name.equals("asOf") || name.equals("version") || FLAG.matcher(name).matches()) names.remove();
            }
            obj.elements().forEachRemaining(AsOfParityTest::strip);
        } else if (node != null && node.isArray()) {
            node.forEach(AsOfParityTest::strip);
        }
    }
}
