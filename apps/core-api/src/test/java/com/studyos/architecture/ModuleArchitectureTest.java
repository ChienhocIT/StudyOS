package com.studyos.architecture;
import com.tngtech.archunit.junit.*;import com.tngtech.archunit.lang.ArchRule;import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
@AnalyzeClasses(packages="com.studyos",importOptions=com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class ModuleArchitectureTest{
    @ArchTest static final ArchRule pureDomain=noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat().resideInAnyPackage("org.springframework..","jakarta..","software.amazon..","com.fasterxml..");
    @ArchTest static final ArchRule controllersUsePorts=noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat().resideInAnyPackage("..infrastructure..","org.springframework.jdbc..","software.amazon..");
    @ArchTest static final ArchRule applicationsAvoidAdapters=noClasses().that().resideInAPackage("..application..").should().dependOnClassesThat().resideInAPackage("..infrastructure..");
}

