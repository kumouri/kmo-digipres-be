package com.kumouri.kmodigipresbe.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fails the build if any production class in com.kumouri.kmodigipresbe references
 * {@link ThreadLocal}. Under WebFlux, the tenant context must live in the Reactor
 * Context — a ThreadLocal will silently leak across requests because operators hop
 * threads. See {@code tenancy/package-info.java}.
 */
class BannedThreadLocalArchTest {

    @Test
    void productionCodeMustNotReferenceThreadLocal() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.kumouri.kmodigipresbe");

        ArchRule rule = noClasses()
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ThreadLocal")
                .because("Tenant context must live in the Reactor Context, not a ThreadLocal");

        rule.check(classes);
    }
}
