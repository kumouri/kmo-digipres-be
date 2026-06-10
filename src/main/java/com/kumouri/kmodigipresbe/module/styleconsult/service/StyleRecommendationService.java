package com.kumouri.kmodigipresbe.module.styleconsult.service;

import com.kumouri.kmodigipresbe.model.catalog.Product;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.styleconsult.model.RetailRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.ServiceRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributes;
import com.kumouri.kmodigipresbe.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — S2: the <strong>pure, deterministic</strong> recommendation engine.
 * Given the read {@link StyleAttributes}, it suggests salon <strong>services</strong> (from the tenant's
 * {@link ServiceMenu}, matched by style→service keyword rules) and <strong>retail products</strong>
 * (from the catalog, <strong>ranked margin-aware</strong>). It reads the salon menu + the product
 * catalog through their <strong>unchanged</strong> repositories — both stay empty-diff; this is an
 * additive reader.
 *
 * <h2>Margin-aware retail ranking (the headline correctness property)</h2>
 * Retail candidates are the tenant's {@code active} catalog products that are not pure services
 * ({@code type != SERVICE}); they are ranked by <strong>margin descending</strong>
 * ({@code margin = unitPrice − unitCost}; a null {@code unitCost} ⇒ margin {@code 0}, ranked last; ties
 * broken by higher price). So the salon's highest-margin take-home products surface first — the
 * retail-attach revenue lever. Top-N (config {@code maxProducts}).
 *
 * <h2>Service matching</h2>
 * Services are matched from the tenant's {@code ServiceMenu}s by mapping the read
 * {@code styleCategory}/{@code color} to service-name keywords (e.g. "balayage" → a menu item whose
 * name contains "balayage"/"color"/"highlight"). A consult that matches nothing legible still gets the
 * salon's general "cut &amp; style"-ish items (or, failing that, the first menu items) so a consult is
 * never empty — AI is triage, not truth. Top-N (config {@code maxServices}).
 *
 * <h2>The never-auto-charge guardrail</h2>
 * Every {@link ServiceRecommendation} and {@link RetailRecommendation} rationale carries the central
 * {@link #STYLIST_CONFIRM_NOTE} — set centrally here so it can never be omitted (a release-blocking
 * fence): a recommendation is a suggestion a human stylist confirms at the visit; nothing is charged
 * automatically.
 *
 * <p>Hand-constructed as a {@code @Bean}; runs under the synthetic widget {@code TenantContext} the
 * orchestrator establishes (the repository finders carry explicit {@code tenantId} predicates).
 */
@Slf4j
public class StyleRecommendationService {

    /** The central never-auto-charge guardrail appended to EVERY recommendation rationale. */
    public static final String STYLIST_CONFIRM_NOTE =
            "This is a suggestion — your stylist will confirm what's right for you at your visit; "
            + "nothing is charged automatically.";

    private final ServiceMenuRepository menus;
    private final ProductRepository products;
    private final int maxServices;
    private final int maxProducts;

    public StyleRecommendationService(ServiceMenuRepository menus,
                                      ProductRepository products,
                                      int maxServices,
                                      int maxProducts) {
        this.menus = menus;
        this.products = products;
        this.maxServices = Math.max(1, maxServices);
        this.maxProducts = Math.max(1, maxProducts);
    }

    /** The composed result: matched services + margin-ranked retail. */
    public record Recommendations(List<ServiceRecommendation> services,
                                  List<RetailRecommendation> retail) {
    }

    /**
     * Compose the service + margin-aware retail recommendations for the read style attributes. Pure +
     * deterministic over the tenant's menu + catalog; never throws on an empty menu/catalog (returns
     * empty lists). Must run under a {@code TenantContext}.
     */
    public Mono<Recommendations> recommend(UUID tenantId, StyleAttributes attributes) {
        Mono<List<ServiceRecommendation>> services = menus.findAllByTenantId(tenantId)
                .collectList()
                .map(menuList -> matchServices(menuList, attributes));
        Mono<List<RetailRecommendation>> retail = products.findAll()
                .collectList()
                .map(this::rankRetailByMargin);
        return Mono.zip(services, retail)
                .map(t -> new Recommendations(t.getT1(), t.getT2()));
    }

    // ── service matching ──────────────────────────────────────────────────────

    private List<ServiceRecommendation> matchServices(List<ServiceMenu> menuList, StyleAttributes attrs) {
        List<ServiceMenuItem> all = new ArrayList<>();
        for (ServiceMenu m : menuList) {
            if (m.getServices() != null) {
                for (ServiceMenuItem item : m.getServices()) {
                    if (item != null && item.getId() != null && item.getName() != null) {
                        all.add(item);
                    }
                }
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }

        List<String> wanted = keywordsFor(attrs);
        // Rank: items whose name matches a wanted keyword first (in keyword order), then the rest in
        // menu order — so a consult always gets SOME services even when nothing matched (cut & style).
        List<ServiceMenuItem> matched = new ArrayList<>();
        for (String kw : wanted) {
            for (ServiceMenuItem item : all) {
                if (!matched.contains(item) && nameContains(item, kw)) {
                    matched.add(item);
                }
            }
        }
        for (ServiceMenuItem item : all) {
            if (!matched.contains(item)) {
                matched.add(item);
            }
        }

        List<ServiceRecommendation> recs = new ArrayList<>();
        for (ServiceMenuItem item : matched) {
            if (recs.size() >= maxServices) break;
            recs.add(ServiceRecommendation.builder()
                    .serviceMenuItemId(item.getId())
                    .name(item.getName())
                    .price(item.getPrice())
                    .rationale(serviceRationale(item, attrs))
                    .build());
        }
        return recs;
    }

    /** Style/color → service-name keywords (deterministic; order is the match priority). */
    private static List<String> keywordsFor(StyleAttributes attrs) {
        List<String> kws = new ArrayList<>();
        String style = lower(attrs == null ? null : attrs.getStyleCategory());
        String color = lower(attrs == null ? null : attrs.getColor());
        if (style != null) {
            if (style.contains("balayage")) addAll(kws, "balayage", "color", "highlight");
            if (style.contains("highlight")) addAll(kws, "highlight", "color", "foil");
            if (style.contains("color") || style.contains("colour")) addAll(kws, "color", "colour", "gloss");
            if (style.contains("blonde") || style.contains("blond")) addAll(kws, "blonde", "color", "toner", "gloss");
            if (style.contains("keratin") || style.contains("smooth")) addAll(kws, "keratin", "smoothing", "treatment");
            if (style.contains("curl") || style.contains("perm")) addAll(kws, "curl", "perm", "style");
            if (style.contains("cut") || style.contains("bob") || style.contains("trim")
                    || style.contains("lob") || style.contains("layer")) addAll(kws, "cut", "trim", "style");
            if (style.contains("extension")) addAll(kws, "extension");
            if (style.contains("gloss") || style.contains("shine")) addAll(kws, "gloss", "shine");
            // the raw style category itself is always a candidate keyword
            addAll(kws, style);
        }
        if (color != null) {
            addAll(kws, "color", "colour");
            if (color.contains("blonde") || color.contains("blond")) addAll(kws, "blonde", "toner");
        }
        // a universal fallback so cut & style services surface even with no specific signal
        addAll(kws, "cut", "style");
        return kws;
    }

    private static String serviceRationale(ServiceMenuItem item, StyleAttributes attrs) {
        String style = attrs == null ? null : attrs.getStyleCategory();
        StringBuilder sb = new StringBuilder();
        if (style != null && !style.isBlank()) {
            sb.append("Matches your ").append(style.trim()).append(" inspiration. ");
        } else {
            sb.append("A great fit for your look. ");
        }
        sb.append(STYLIST_CONFIRM_NOTE);
        return sb.toString();
    }

    // ── margin-aware retail ranking (the headline property) ─────────────────────

    private List<RetailRecommendation> rankRetailByMargin(List<Product> all) {
        List<Product> candidates = new ArrayList<>();
        for (Product p : all) {
            if (p == null || !p.isActive()) continue;
            if (p.getType() == Product.ProductType.SERVICE) continue; // services are booked, not sold as retail
            candidates.add(p);
        }
        // Rank by margin DESC (null cost ⇒ margin 0, ranked last), ties broken by higher price.
        candidates.sort(Comparator
                .comparing((Product p) -> margin(p), Comparator.reverseOrder())
                .thenComparing(p -> price(p), Comparator.reverseOrder()));

        List<RetailRecommendation> recs = new ArrayList<>();
        for (Product p : candidates) {
            if (recs.size() >= maxProducts) break;
            BigDecimal m = margin(p);
            recs.add(RetailRecommendation.builder()
                    .productId(p.getId())
                    .sku(p.getSku())
                    .name(p.getName())
                    .price(p.getUnitPrice())
                    .cost(p.getUnitCost())
                    .marginAmount(m)
                    .rationale("Recommended take-home product to maintain your look. " + STYLIST_CONFIRM_NOTE)
                    .build());
        }
        return recs;
    }

    /** {@code unitPrice − unitCost}; a null price or cost contributes 0 (unknown ⇒ ranked last). */
    static BigDecimal margin(Product p) {
        BigDecimal price = p.getUnitPrice();
        BigDecimal cost = p.getUnitCost();
        if (price == null || cost == null) {
            return BigDecimal.ZERO;
        }
        return price.subtract(cost);
    }

    private static BigDecimal price(Product p) {
        return p.getUnitPrice() == null ? BigDecimal.ZERO : p.getUnitPrice();
    }

    private static boolean nameContains(ServiceMenuItem item, String keyword) {
        String name = lower(item.getName());
        return name != null && keyword != null && name.contains(keyword);
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.US);
    }

    private static void addAll(List<String> target, String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank() && !target.contains(v)) {
                target.add(v);
            }
        }
    }
}
