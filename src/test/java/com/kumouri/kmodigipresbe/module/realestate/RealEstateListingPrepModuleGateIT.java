package com.kumouri.kmodigipresbe.module.realestate;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.ListingPrepService;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.SocialCalendarGenerationService;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.controller.ListingPrepController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T10 (Real Estate "Listing Prep Studio") — module-gate composition. The T10 beans + controller ride the
 * shipped {@code realestate} flagship module gate ({@code @ConditionalOnProperty(kmosf.modules.realestate.enabled)}),
 * so they exist only when the module is on (the RE-4 / Midnight-Responder posture). The
 * {@code MidnightResponderModuleGateIT} bean-presence pattern, across two contexts.
 */
class RealEstateListingPrepModuleGateIT {

    /** realestate OFF (default) → no T10 beans / controller. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    static class RealEstateOff {
        @Autowired ApplicationContext ctx;

        @Test
        void t10BeansAbsent_whenRealEstateOff() {
            assertThatThrownBy(() -> ctx.getBean(ListingPrepService.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(SocialCalendarGenerationService.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(ListingPrepController.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    /** realestate ON → the T10 beans + controller are present. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = "kmosf.modules.realestate.enabled=true")
    static class RealEstateOn {
        @Autowired ApplicationContext ctx;

        @Test
        void t10BeansPresent_whenRealEstateOn() {
            assertThat(ctx.getBean(ListingPrepService.class)).isNotNull();
            assertThat(ctx.getBean(SocialCalendarGenerationService.class)).isNotNull();
            assertThat(ctx.getBean(ListingPrepController.class)).isNotNull();
        }
    }
}
