package com.kumouri.kmodigipresbe.module.styleconsult;

import com.kumouri.kmodigipresbe.model.catalog.Product;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.styleconsult.model.RetailRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.ServiceRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributeSource;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributes;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleRecommendationService;
import com.kumouri.kmodigipresbe.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T9 — {@link StyleRecommendationService} pure-engine unit test (no Docker, fast). The marquee proof is
 * the <strong>margin-aware retail ranking</strong>: products are returned highest-margin first
 * ({@code unitPrice − unitCost}), a null-cost product is ranked last, and the central
 * {@link StyleRecommendationService#STYLIST_CONFIRM_NOTE} guardrail appears on every recommendation.
 * Also proves the style→service keyword mapping and the always-non-empty service fallback. The reactive
 * repos are Mockito-stubbed (no Spring context, no Mongo).
 */
class StyleRecommendationServiceTest {

    private final ServiceMenuRepository menus = Mockito.mock(ServiceMenuRepository.class);
    private final ProductRepository products = Mockito.mock(ProductRepository.class);
    private final UUID tenantId = UUID.randomUUID();

    private StyleRecommendationService service(int maxServices, int maxProducts) {
        return new StyleRecommendationService(menus, products, maxServices, maxProducts);
    }

    private StyleAttributes balayage() {
        return StyleAttributes.builder()
                .styleCategory("balayage").length("long").texture("wavy").color("blonde")
                .source(StyleAttributeSource.VISION).confidence(1.0)
                .build();
    }

    private ServiceMenu menu(ServiceMenuItem... items) {
        return ServiceMenu.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .name("menu").services(List.of(items)).build();
    }

    private ServiceMenuItem item(String id, String name, String price) {
        return ServiceMenuItem.builder().id(id).name(name).price(new BigDecimal(price)).build();
    }

    private Product product(String name, String price, String cost) {
        return Product.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).sku("SKU-" + name).name(name)
                .unitPrice(new BigDecimal(price))
                .unitCost(cost == null ? null : new BigDecimal(cost))
                .type(Product.ProductType.GOOD).active(true)
                .build();
    }

    // ── the headline: margin-aware retail ranking ──────────────────────────────

    @Test
    void retail_rankedByMarginDescending_highestMarginFirst() {
        when(menus.findAllByTenantId(tenantId)).thenReturn(Flux.empty());
        // Intentionally NOT in margin order on the wire — the ranker must re-sort.
        when(products.findAll()).thenReturn(Flux.just(
                product("Leave-In", "22", "8"),     // margin 14
                product("Bond Builder", "38", "15"), // margin 23 (highest)
                product("Heat Protectant", "24", "9"), // margin 15
                product("Purple Shampoo", "28", "11"))); // margin 17

        StyleRecommendationService.Recommendations recs =
                service(3, 4).recommend(tenantId, balayage()).block();

        assertThat(recs).isNotNull();
        List<RetailRecommendation> retail = recs.retail();
        assertThat(retail).hasSize(4);
        assertThat(retail).extracting(RetailRecommendation::getName)
                .containsExactly("Bond Builder", "Purple Shampoo", "Heat Protectant", "Leave-In");
        // Margins are computed + descending.
        assertThat(retail.get(0).getMarginAmount()).isEqualByComparingTo("23");
        assertThat(retail.get(1).getMarginAmount()).isEqualByComparingTo("17");
        assertThat(retail.get(2).getMarginAmount()).isEqualByComparingTo("15");
        assertThat(retail.get(3).getMarginAmount()).isEqualByComparingTo("14");
        // Every retail rec carries the never-auto-charge guardrail.
        assertThat(retail).allSatisfy(r ->
                assertThat(r.getRationale()).contains(StyleRecommendationService.STYLIST_CONFIRM_NOTE));
    }

    @Test
    void retail_nullCost_isRankedLast_marginZero() {
        when(menus.findAllByTenantId(tenantId)).thenReturn(Flux.empty());
        when(products.findAll()).thenReturn(Flux.just(
                product("Unknown-Cost Premium", "60", null), // null cost -> margin 0 -> LAST despite high price
                product("Bond Builder", "38", "15")));       // margin 23

        StyleRecommendationService.Recommendations recs =
                service(3, 3).recommend(tenantId, balayage()).block();

        assertThat(recs).isNotNull();
        List<RetailRecommendation> retail = recs.retail();
        assertThat(retail).extracting(RetailRecommendation::getName)
                .containsExactly("Bond Builder", "Unknown-Cost Premium");
        assertThat(retail.get(1).getMarginAmount()).isEqualByComparingTo("0");
        assertThat(retail.get(1).getCost()).isNull();
    }

    @Test
    void retail_excludesServiceTypeProducts_andInactive() {
        when(menus.findAllByTenantId(tenantId)).thenReturn(Flux.empty());
        Product serviceTyped = Product.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .sku("SVC").name("A Service SKU").unitPrice(new BigDecimal("100"))
                .unitCost(new BigDecimal("1")).type(Product.ProductType.SERVICE).active(true).build();
        Product inactive = Product.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .sku("OLD").name("Discontinued").unitPrice(new BigDecimal("99"))
                .unitCost(new BigDecimal("1")).type(Product.ProductType.GOOD).active(false).build();
        when(products.findAll()).thenReturn(Flux.just(serviceTyped, inactive,
                product("Bond Builder", "38", "15")));

        StyleRecommendationService.Recommendations recs =
                service(3, 5).recommend(tenantId, balayage()).block();

        assertThat(recs).isNotNull();
        assertThat(recs.retail()).extracting(RetailRecommendation::getName)
                .containsExactly("Bond Builder"); // service-typed + inactive both excluded
    }

    @Test
    void retail_respectsMaxProductsCap() {
        when(menus.findAllByTenantId(tenantId)).thenReturn(Flux.empty());
        when(products.findAll()).thenReturn(Flux.just(
                product("A", "40", "10"), product("B", "35", "10"),
                product("C", "30", "10"), product("D", "25", "10")));

        StyleRecommendationService.Recommendations recs =
                service(3, 2).recommend(tenantId, balayage()).block();

        assertThat(recs).isNotNull();
        assertThat(recs.retail()).hasSize(2);
        assertThat(recs.retail()).extracting(RetailRecommendation::getName).containsExactly("A", "B");
    }

    // ── service matching ────────────────────────────────────────────────────────

    @Test
    void services_matchStyleKeyword_balayageFirst_thenFillWithRest() {
        when(menus.findAllByTenantId(tenantId)).thenReturn(Flux.just(menu(
                item("svc-cut", "Cut & Style", "65"),
                item("svc-balayage", "Balayage", "185"),
                item("svc-gloss", "Gloss & Tone", "75"))));
        when(products.findAll()).thenReturn(Flux.empty());

        StyleRecommendationService.Recommendations recs =
                service(2, 3).recommend(tenantId, balayage()).block();

        assertThat(recs).isNotNull();
        List<ServiceRecommendation> services = recs.services();
        assertThat(services).hasSize(2);
        // Balayage matches the style keyword and surfaces first.
        assertThat(services.get(0).getName()).isEqualTo("Balayage");
        assertThat(services).allSatisfy(s ->
                assertThat(s.getRationale()).contains(StyleRecommendationService.STYLIST_CONFIRM_NOTE));
    }

    @Test
    void services_noMatch_stillReturnsMenuItems_neverEmptyWhenMenuHasItems() {
        // A style with no keyword overlap with the menu names → fallback to menu items (never empty).
        when(menus.findAllByTenantId(tenantId)).thenReturn(Flux.just(menu(
                item("svc-cut", "Cut & Style", "65"),
                item("svc-keratin", "Keratin Smoothing", "220"))));
        when(products.findAll()).thenReturn(Flux.empty());

        StyleAttributes obscure = StyleAttributes.builder()
                .styleCategory("space buns").source(StyleAttributeSource.MANUAL).confidence(1.0).build();

        StyleRecommendationService.Recommendations recs =
                service(3, 3).recommend(tenantId, obscure).block();

        assertThat(recs).isNotNull();
        assertThat(recs.services()).isNotEmpty();
        assertThat(recs.services()).extracting(ServiceRecommendation::getName)
                .containsAnyOf("Cut & Style", "Keratin Smoothing");
    }

    @Test
    void emptyMenuAndCatalog_returnsEmptyLists_neverThrows() {
        when(menus.findAllByTenantId(any())).thenReturn(Flux.empty());
        when(products.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(service(3, 3).recommend(tenantId, balayage()))
                .assertNext(recs -> {
                    assertThat(recs.services()).isEmpty();
                    assertThat(recs.retail()).isEmpty();
                })
                .verifyComplete();
    }
}
