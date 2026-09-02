package com.geneinvoice.product;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The server-side product search contract: the optional parameter is trimmed,
 * absent/empty/whitespace-only text keeps the findAll path, anything else goes
 * through the case-insensitive name-or-description query, and every response —
 * including a successful no-match — remains the bare ProductDto array.
 */
class ProductSearchControllerTest {

    private final ProductRepository repository = mock(ProductRepository.class);
    private final ProductController controller = new ProductController(repository);

    private static final Product DESK = Product.builder()
            .id(1L)
            .name("Office Desk")
            .description("Sturdy wooden workstation")
            .price(new BigDecimal("199.99"))
            .active(true)
            .build();

    private static final ProductController.ProductDto DESK_DTO = new ProductController.ProductDto(
            1L, "Office Desk", "Sturdy wooden workstation", new BigDecimal("199.99"), true);

    @Test
    void absentEmptyOrWhitespaceOnlySearchListsEveryProduct() {
        when(repository.findAll()).thenReturn(List.of(DESK));

        List<ProductController.ProductDto> expected = List.of(DESK_DTO);
        assertEquals(expected, controller.list(null));
        assertEquals(expected, controller.list(""));
        assertEquals(expected, controller.list("   \t\n  "));
        verify(repository, org.mockito.Mockito.times(3)).findAll();
        verify(repository, never())
                .findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(any(), any());
    }

    @Test
    void nonEmptySearchIsTrimmedAndSearchedAcrossNameAndDescriptionOnTheServer() {
        when(repository.findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(any(), any()))
                .thenReturn(List.of(DESK));

        List<ProductController.ProductDto> result = controller.list("  Desk  ");

        // AC12: the server receives the trimmed term once per field — name OR
        // description — under the ContainingIgnoreCase derived query.
        verify(repository).findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase("Desk", "Desk");
        verify(repository, never()).findAll();
        assertEquals(List.of(DESK_DTO), result);
    }

    @Test
    void whitespaceAroundTextIsRemovedBeforeMatching() {
        when(repository.findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(any(), any()))
                .thenReturn(List.of());

        controller.list("\tleather chair \n");

        verify(repository).findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
                "leather chair", "leather chair");
    }

    @Test
    void successfulSearchWithNoMatchesIsAnEmptyBareArrayNotAnError() {
        when(repository.findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(any(), any()))
                .thenReturn(List.of());

        List<ProductController.ProductDto> result = controller.list("no-such-product");

        assertEquals(List.of(), result);
    }

    @Test
    void longSearchTextFollowsTheOrdinaryQueryWithNoSpecialCap() {
        String longText = "product-".repeat(500);
        when(repository.findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(any(), any()))
                .thenReturn(List.of());

        assertEquals(List.of(), controller.list(longText));
        // The full, untruncated text reaches the query — no rejection, no cap.
        verify(repository).findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(longText, longText);
    }

    @Test
    void endpointRetainsProductViewAndTheBareArrayContract() throws NoSuchMethodException {
        RequestMapping mapping = ProductController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[]{"/api/products"}, mapping.value());

        Method list = ProductController.class.getMethod("list", String.class);
        PreAuthorize guard = list.getAnnotation(PreAuthorize.class);
        assertNotNull(guard);
        assertEquals("hasAuthority('PRODUCT_VIEW')", guard.value());
        // Bare JSON array, not a wrapper or paged envelope.
        assertEquals(List.class, list.getReturnType());

        RequestParam search = list.getParameters()[0].getAnnotation(RequestParam.class);
        assertNotNull(search);
        assertFalse(search.required());
    }
}
