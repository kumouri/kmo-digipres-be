package com.kumouri.kmodigipresbe.module.restaurantlight;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.CateringOrder;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.MenuItem;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.Reservation;
import com.kumouri.kmodigipresbe.module.restaurantlight.repository.CateringOrderRepository;
import com.kumouri.kmodigipresbe.module.restaurantlight.repository.MenuItemRepository;
import com.kumouri.kmodigipresbe.module.restaurantlight.repository.ReservationRepository;
import com.kumouri.kmodigipresbe.module.restaurantlight.service.CateringOrderService;
import com.kumouri.kmodigipresbe.module.restaurantlight.service.MenuItemService;
import com.kumouri.kmodigipresbe.module.restaurantlight.service.ReservationService;
import com.kumouri.kmodigipresbe.service.quote.QuoteService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Restaurant-light vertical module. Loaded only when
 * {@code kmosf.modules.restaurant-light.enabled=true}.
 *
 * <p>Adds {@link MenuItem}, {@link CateringOrder}, and {@link Reservation}
 * — Phase 14 generalises catering and lightweight reservation management for
 * restaurant and food-service clients. Composes Phase-7 {@link QuoteService}
 * for the catering quote-issuance flow and Phase-9 {@code PublicWidgetTokenService}
 * for anonymous reservation submissions.
 *
 * <p>This module has no hard dependency on any other vertical module (field-service,
 * home-services, salon-spa). It can be enabled independently after Phase 10.
 *
 * <p>Build is gated behind a paying lead — enable only when onboarding a
 * restaurant or catering client. See CRM buildout plan Phase 14 for context.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.restaurant-light", name = "enabled")
public class RestaurantLightAutoConfiguration {

    public static final String MODULE_KEY = "restaurant-light";

    @Bean
    public ModuleDefinition restaurantLightModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Restaurant Light", "1.0.0",
                List.of("MENU_ITEM", "CATERING_ORDER", "RESERVATION"));
    }

    @Bean
    public MenuItemService menuItemService(MenuItemRepository items) {
        return new MenuItemService(items);
    }

    @Bean
    public CateringOrderService cateringOrderService(CateringOrderRepository orders,
                                                     QuoteService quotes,
                                                     DomainEventPublisher events) {
        return new CateringOrderService(orders, quotes, events);
    }

    @Bean
    public ReservationService reservationService(ReservationRepository reservations,
                                                 DomainEventPublisher events) {
        return new ReservationService(reservations, events);
    }
}
