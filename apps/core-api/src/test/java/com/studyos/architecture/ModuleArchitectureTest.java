package com.studyos.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ModuleArchitectureTest {
    private static final JavaClasses CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.studyos");

    @Test
    void domainDoesNotDependOnFrameworks() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "software.amazon..",
                        "com.fasterxml..")
                .check(CLASSES);
    }

    @Test
    void controllersUseApplicationInterfaces() {
        noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..infrastructure..", "org.springframework.jdbc..", "software.amazon..")
                .check(CLASSES);
    }

    @Test
    void applicationsDoNotDependOnAdapters() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..infrastructure..")
                .check(CLASSES);
    }

    @Test
    void modulesDoNotReachIntoOtherModulesAdapters() {
        for (String module :
                new String[] {
                    "identity",
                    "workspace",
                    "notebook",
                    "source",
                    "conversation",
                    "note",
                    "studio",
                    "review",
                    "learning",
                    "language",
                    "analytics"
                }) {
            noClasses()
                    .that()
                    .resideOutsideOfPackages("com.studyos." + module + "..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.studyos." + module + ".infrastructure..")
                    .check(CLASSES);
        }
    }
}
