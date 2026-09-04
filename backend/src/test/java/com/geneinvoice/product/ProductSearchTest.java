package com.geneinvoice.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR11 / AC14 / AC16 / AC17 plus the inherited PRODUCT_VIEW rule: GET /api/products accepts an
 * optional search text, matches it case-insensitively as a substring of name OR description on the
 * server, an absent or whitespace-only text returns the unchanged full list, and a successful query
 * with no matches returns an empty list rather than an error.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class ProductSearchTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProductRepository productRepository;

    private static final AtomicLong SEQ = new AtomicLong();

    private String loginAdmin() throws Exception {
        var res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private Product saveProduct(String name, String description) {
        return productRepository.save(Product.builder()
                .name(name)
                .description(description)
                .price(new BigDecimal("9.99"))
                .active(true)
                .build());
    }

    private JsonNode listProducts(String token, String search) throws Exception {
        MockHttpServletRequestBuilder req = get("/api/products").header("Authorization", "Bearer " + token);
        if (search != null) req = req.param("search", search);
        var res = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private static Set<Long> ids(JsonNode array) {
        Set<Long> ids = new HashSet<>();
        array.forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    @Test
    void absentOrWhitespaceOnlySearchReturnsEveryProduct() throws Exception {
        String token = loginAdmin();
        long total = productRepository.count();

        JsonNode all = listProducts(token, null);
        assertThat(all.isArray()).isTrue();
        assertThat(all.size()).isEqualTo((int) total);

        JsonNode whitespace = listProducts(token, "   \t  ");
        assertThat(whitespace.size()).isEqualTo((int) total);
        assertThat(ids(whitespace)).isEqualTo(ids(all));
    }

    @Test
    void searchMatchesNameOrDescriptionIgnoringCase() throws Exception {
        String token = loginAdmin();
        String tag = "Zxq" + SEQ.incrementAndGet();
        Product byName = saveProduct(tag + " Stapler", "Desk accessory");
        Product byDesc = saveProduct(TAG_IRRELEVANT_NAME + SEQ.incrementAndGet(), "Works with " + tag.toLowerCase() + " machines");
        saveProduct("Unrelated Mug " + SEQ.incrementAndGet(), "Kitchenware");

        JsonNode result = listProducts(token, tag.toUpperCase());

        assertThat(ids(result)).containsExactlyInAnyOrder(byName.getId(), byDesc.getId());
        for (JsonNode node : result) {
            assertThat(node.has("id")).isTrue();
            assertThat(node.has("name")).isTrue();
            assertThat(node.has("description")).isTrue();
            assertThat(node.has("price")).isTrue();
            assertThat(node.has("active")).isTrue();
        }
    }

    private static final String TAG_IRRELEVANT_NAME = "Copy Paper ";

    @Test
    void productWithNullDescriptionStillMatchesByName() throws Exception {
        String token = loginAdmin();
        String tag = "NullDesc" + SEQ.incrementAndGet();
        Product p = saveProduct(tag + " Gadget", null);

        JsonNode result = listProducts(token, tag.toLowerCase());

        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).get("id").asLong()).isEqualTo(p.getId());
        assertThat(result.get(0).get("name").asText()).isEqualTo(tag + " Gadget");
    }

    @Test
    void successfulSearchWithNoMatchesReturnsEmptyListNotError() throws Exception {
        String token = loginAdmin();

        JsonNode result = listProducts(token, "qqx-nosuch-" + SEQ.incrementAndGet());

        assertThat(result.isArray()).isTrue();
        assertThat(result.size()).isZero();
    }
}
