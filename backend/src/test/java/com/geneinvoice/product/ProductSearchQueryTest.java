package com.geneinvoice.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real-JPA verification of the derived name-or-description search: the match
 * is case-insensitive, the text may occur anywhere in either field, a null
 * description does not break the name arm, and a successful no-match — even
 * for unusually long text — is an error-free empty list.
 */
// The workspace's purpose-built h2 profile (in-memory H2 + H2Dialect +
// create-drop); without it the default dev profile would force the PostgreSQL
// dialect onto the embedded test database.
@DataJpaTest
@ActiveProfiles("h2")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductSearchQueryTest {

    @Autowired
    ProductRepository products;

    private void seed() {
        products.save(Product.builder()
                .name("Office Desk")
                .description("Sturdy wooden workstation")
                .price(new BigDecimal("199.99"))
                .active(true)
                .build());
        products.save(Product.builder()
                .name("desk chair")
                .description(null)
                .price(new BigDecimal("89.50"))
                .active(true)
                .build());
        products.save(Product.builder()
                .name("Notebook")
                .description("A ruled DESK pad for notes")
                .price(new BigDecimal("4.25"))
                .active(true)
                .build());
        products.save(Product.builder()
                .name("Ballpoint Pen")
                .description("Writes smoothly")
                .price(new BigDecimal("1.10"))
                .active(true)
                .build());
    }

    private List<String> names(String term) {
        return products.findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(term, term)
                .stream().map(Product::getName).sorted().toList();
    }

    @Test
    void matchesAnywhereInNameOrDescriptionIgnoringCase() {
        seed();

        // AC12: exactly the products whose name OR description contains the
        // text anywhere, regardless of case.
        assertEquals(List.of("Notebook", "Office Desk", "desk chair"), names("desk"));
        assertEquals(List.of("Notebook", "Office Desk", "desk chair"), names("DESK"));
        assertEquals(List.of("desk chair"), names("hair"));
        assertEquals(List.of("Notebook"), names("RULED"));
        // A product whose description is null still matches on its name.
        assertEquals(List.of("desk chair"), names("chair"));
    }

    @Test
    void successfulNoMatchIsAnErrorFreeEmptyListAndLongTextNeedsNoSpecialBranch() {
        seed();

        // AC13: a completed search that matched nothing returns an empty list.
        assertEquals(List.of(), names("wheelbarrow"));
        // AC13: unusually long text follows the same matching rule — no cap,
        // no rejection, no error; just the ordinary empty result.
        assertEquals(List.of(), names("an-extremely-long-search-text-".repeat(200)));
    }

    @Test
    void emptyResultRemainsEmptyAndSearchNeverWidensToAllProducts() {
        seed();

        // The findAll rows stay out of a non-matching search: the full list is
        // only the trimmed-empty path's answer, never the query's.
        assertEquals(4, products.findAll().size());
        assertEquals(List.of(), names("wheelbarrow"));
    }
}
