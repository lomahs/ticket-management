package tracker;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Layers: domain ← application ← adapters ← config. The domain is plain Java; the application uses Spring only for
 * components and transactions, never web, JDBC or JSON.
 */
@AnalyzeClasses(packages = "tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layers = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("config").definedBy("tracker.config..")
            .layer("adapters").definedBy("tracker.adapters..")
            .layer("application").definedBy("tracker.application..")
            .layer("domain").definedBy("tracker.domain..")
            .whereLayer("config").mayNotBeAccessedByAnyLayer()
            .whereLayer("adapters").mayOnlyBeAccessedByLayers("config")
            .whereLayer("application").mayOnlyBeAccessedByLayers("adapters", "config")
            .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "adapters", "config");

    @ArchTest
    static final ArchRule plainDomain = noClasses().that().resideInAPackage("tracker.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "com.fasterxml..",
                    "java.sql..", "javax.sql..", "java.net..", "java.nio.file..", "org.slf4j..");

    @ArchTest
    static final ArchRule applicationWithoutInfrastructure = noClasses().that().resideInAPackage("tracker.application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "org.springframework.jdbc..",
                    "org.springframework.http..", "jakarta.servlet..", "jakarta.validation..", "com.fasterxml..", "java.sql..",
                    "javax.sql..", "java.net.http..", "org.apache.poi..", "org.sqlite..", "com.zaxxer..");
}
