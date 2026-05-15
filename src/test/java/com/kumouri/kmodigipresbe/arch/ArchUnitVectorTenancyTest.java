package com.kumouri.kmodigipresbe.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Verifies that the {@code VectorIndex} interface (and any class that implements
 * it) cannot define a {@code search} method that omits the mandatory
 * {@code tenantId} first parameter.
 *
 * <p>A vector search without a tenant filter would be a catastrophic data-
 * isolation bug — every tenant's embeddings would be visible to every other
 * tenant. This guard ensures that oversight cannot be introduced silently.
 *
 * <p>The rule is structural, not behavioural: it checks method signatures at
 * compile-time so there is no way to bypass it by accident.
 */
class ArchUnitVectorTenancyTest {

    @Test
    void vectorIndexSearchMustAlwaysReceiveTenantId() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.kumouri.kmodigipresbe");

        noClasses()
                .that().implement(
                        com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex.class)
                .or().areAssignableTo(
                        com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex.class)
                .should(new ArchCondition<>("declare a 'search' method without UUID as first parameter") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaClass clazz,
                                      ConditionEvents events) {
                        for (JavaMethod method : clazz.getMethods()) {
                            if (!"search".equals(method.getName())) continue;
                            boolean firstParamIsUuid = !method.getParameterTypes().isEmpty()
                                    && method.getParameterTypes().get(0)
                                        .toErasure().getFullName().equals("java.util.UUID");
                            if (!firstParamIsUuid) {
                                events.add(SimpleConditionEvent.violated(method,
                                        "VectorIndex.search method in "
                                                + clazz.getName()
                                                + " does not have UUID as first parameter — "
                                                + "tenant isolation would be broken"));
                            }
                        }
                    }
                })
                .because("VectorIndex.search must always take tenantId (UUID) as its first argument " +
                        "to prevent cross-tenant vector leakage")
                .check(classes);
    }

    @Test
    void embeddingServiceEmbedMustAlwaysReceiveTenantId() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.kumouri.kmodigipresbe");

        noClasses()
                .that().implement(
                        com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService.class)
                .or().areAssignableTo(
                        com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService.class)
                .should(new ArchCondition<>("declare an 'embed' method without UUID as first parameter") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaClass clazz,
                                      ConditionEvents events) {
                        for (JavaMethod method : clazz.getMethods()) {
                            if (!"embed".equals(method.getName())) continue;
                            boolean firstParamIsUuid = !method.getParameterTypes().isEmpty()
                                    && method.getParameterTypes().get(0)
                                        .toErasure().getFullName().equals("java.util.UUID");
                            if (!firstParamIsUuid) {
                                events.add(SimpleConditionEvent.violated(method,
                                        "EmbeddingService.embed method in "
                                                + clazz.getName()
                                                + " does not have UUID as first parameter — "
                                                + "embedding cost would not be charged to any tenant"));
                            }
                        }
                    }
                })
                .because("EmbeddingService.embed must always take tenantId (UUID) as its first argument")
                .check(classes);
    }
}
