package com.kumouri.kmodigipresbe.module.salonspa;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.LoyaltyAccountRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.LoyaltyTransactionRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.module.salonspa.service.BookingPolicyService;
import com.kumouri.kmodigipresbe.module.salonspa.service.LoyaltyAccrualService;
import com.kumouri.kmodigipresbe.module.salonspa.service.RebookingNudgeService;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonMenuService;
import com.kumouri.kmodigipresbe.module.salonspa.service.StaffMemberService;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.repository.SequenceRepository;
import com.kumouri.kmodigipresbe.service.sequence.SequenceCrudService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;

import java.util.List;

/**
 * Salon/spa vertical module. Loaded only when
 * {@code kmosf.modules.salon-spa.enabled=true}.
 *
 * <p>Adds {@link com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu},
 * {@link com.kumouri.kmodigipresbe.module.salonspa.model.Booking}, and
 * {@link com.kumouri.kmodigipresbe.module.salonspa.model.LoyaltyAccount} — Phase 12
 * introduces the salon/spa vertical (hair salons, spas, nail studios, fitness studios)
 * alongside the marketing infrastructure (forms, landing pages, UTM capture).
 *
 * <p>This module has no hard dependency on the field-service or home-services modules;
 * it stands alone on the Phase 7 (products/payments) and Phase 9 (sequences/widgets)
 * platform primitives.
 *
 * <p>Per-sub-PR build-out within Phase 12:
 * <ul>
 *   <li>12a: module skeleton — entities, repositories, and this
 *       {@code AutoConfiguration} registering the {@link ModuleDefinition}.</li>
 *   <li>12b: {@link BookingPolicyService},
 *       {@link SalonBookingService}, {@link SalonMenuService},
 *       {@link StaffMemberService}, booking controllers, and the public
 *       booking widget.</li>
 *   <li><strong>12c (this PR):</strong> {@link LoyaltyAccrualService},
 *       {@link RebookingNudgeService}, loyalty controller.</li>
 *   <li>12d: Square POS integration ({@code SquareAutoConfiguration}).</li>
 *   <li>12e: Marketing infrastructure — {@code FormDefinition}, {@code LandingPage},
 *       UTM capture, {@code FirstTouch} on Contact (core, not module-gated).</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.salon-spa", name = "enabled")
public class SalonSpaAutoConfiguration {

    public static final String MODULE_KEY = "salon-spa";

    @Bean
    public ModuleDefinition salonSpaModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Salon & Spa", "0.3.0",
                List.of("SERVICE_MENU", "BOOKING", "LOYALTY_ACCOUNT"));
    }

    @Bean
    public SalonMenuService salonMenuService(ServiceMenuRepository menus) {
        return new SalonMenuService(menus);
    }

    @Bean
    public StaffMemberService staffMemberService(StaffMemberRepository staff) {
        return new StaffMemberService(staff);
    }

    @Bean
    public BookingPolicyService bookingPolicyService(StaffMemberRepository staff,
                                                      BookingRepository bookings) {
        return new BookingPolicyService(staff, bookings);
    }

    @Bean
    public SalonBookingService salonBookingService(BookingRepository bookings,
                                                    BookingPolicyService policy,
                                                    InvoiceRepository invoiceRepo,
                                                    DomainEventPublisher events) {
        return new SalonBookingService(bookings, policy, invoiceRepo, events);
    }

    @Bean
    public LoyaltyAccrualService loyaltyAccrualService(LoyaltyAccountRepository accounts,
                                                        LoyaltyTransactionRepository transactions,
                                                        DomainEventPublisher events) {
        return new LoyaltyAccrualService(accounts, transactions, events);
    }

    @Bean
    public RebookingNudgeService rebookingNudgeService(SequenceRepository sequences,
                                                        SequenceCrudService sequenceCrud,
                                                        DomainEventPublisher events,
                                                        ReactiveMongoTemplate mongo) {
        return new RebookingNudgeService(sequences, sequenceCrud, events, mongo);
    }

}
