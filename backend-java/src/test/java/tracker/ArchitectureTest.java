package tracker;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Clean architecture layers, as the original backend enforced with import-linter. */
@AnalyzeClasses(packages = "tracker", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layers = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("bootstrap").definedBy("tracker.bootstrap..")
            .layer("adapters").definedBy("tracker.adapters..")
            .layer("application").definedBy("tracker.application..")
            .layer("domain").definedBy("tracker.domain..")
            .whereLayer("bootstrap").mayNotBeAccessedByAnyLayer()
            .whereLayer("adapters").mayOnlyBeAccessedByLayers("bootstrap")
            .whereLayer("application").mayOnlyBeAccessedByLayers("adapters", "bootstrap")
            .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "adapters", "bootstrap");

    /** Domain and application have no framework or IO imports. */
    @ArchTest
    static final ArchRule pure = noClasses().that().resideInAnyPackage("tracker.domain..", "tracker.application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.fasterxml..", "java.sql..",
                    "javax.sql..", "java.net.http..", "org.apache.poi..", "javax.crypto..", "com.zaxxer..", "org.sqlite..",
                    "jakarta..");
}
