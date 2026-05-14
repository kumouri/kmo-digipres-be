package com.kumouri.kmodigipresbe.module.fieldservice;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.fieldservice.service.JobSiteService;
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

class FieldServiceModuleToggleIT {

    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @TestPropertySource(properties = "kmosf.modules.field-service.enabled=false")
    @ExtendWith(SpringExtension.class)
    static class Disabled {
        @Autowired ApplicationContext ctx;

        @Test
        void fieldServiceBeansAbsent() {
            assertThatThrownBy(() -> ctx.getBean(JobSiteService.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThat(ctx.getBeansOfType(ModuleDefinition.class)).isEmpty();
        }
    }

    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @TestPropertySource(properties = {
            "kmosf.modules.field-service.enabled=true",
            "kmosf.files.region=us-east-1"
    })
    @ExtendWith(SpringExtension.class)
    static class Enabled {
        @Autowired ApplicationContext ctx;

        @Test
        void fieldServiceBeansPresentAndRegistered() {
            assertThat(ctx.getBean(JobSiteService.class)).isNotNull();
            assertThat(ctx.getBeansOfType(ModuleDefinition.class))
                    .hasSize(1)
                    .anySatisfy((name, def) -> assertThat(def.key()).isEqualTo("field-service"));
        }
    }
}
