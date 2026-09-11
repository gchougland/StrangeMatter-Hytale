import java.nio.file.Files

plugins {
    `maven-publish`
    id("hytale-mod") version "0.8.1"
}

group = "com.hexvane"
version = "0.9.10"

val hytaleHome = providers.environmentVariable("APPDATA").map { "$it/Hytale/install/release/package/game/latest" }
val localServerJar = providers.gradleProperty("hytaleServerJar").orElse(hytaleHome.map { "$it/Server/HytaleServer.jar" })
val apiClasses = providers.gradleProperty("hytaleApiClasses").orElse(localServerJar)
repositories { mavenCentral() }
// Use the installed release API, including when the build helper stages a local copy.
hytale {
    serverJar.set(layout.file(localServerJar.map { file(it) }))
    assetsFile.set(layout.file(hytaleHome.map { file("$it/Assets.zip") }))
    addServerDependency.set(false)
    addAssetsDependency.set(false)
    disableSentry.set(true)
    runDir.set("run")
}
dependencies { compileOnly(files(apiClasses)); runtimeOnly(files(localServerJar)); testImplementation(files(apiClasses)) }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) }; withSourcesJar() }
idea { module { isDownloadSources = true; isDownloadJavadoc = true } }
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Compile only this plugin's explicit source inputs, never dependency sources.
    options.sourcepath = files("build/empty-sourcepath")
    options.compilerArgs.add("-implicit:none")
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal", "-Xmaxwarns", "10000"))
}
tasks.named<Jar>("jar") {
    archiveBaseName = "StrangeMatter"
    from("LICENSE.txt") { into("META-INF") }
}
tasks.named<Jar>("sourcesJar") { from("LICENSE.txt") { into("META-INF") } }
val verifyGameplay by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.hexvane.strangematter.GameplayVerification"
    systemProperty("java.util.logging.manager", "com.hypixel.hytale.logger.backend.HytaleLogManager")
    enableAssertions = true
}
tasks.test { enabled = false } // Deterministic main-based checks need no external test framework.
val verifyTextureFootprint by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/diagnostics/check_reports.py")
}
val verifyResearchAdmin by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.hexvane.strangematter.research.ResearchAdminVerification"
    systemProperty("java.util.logging.manager", "com.hypixel.hytale.logger.backend.HytaleLogManager")
    enableAssertions = true
}
tasks.check { dependsOn(verifyTextureFootprint, verifyResearchAdmin) }
val verifyFixtureSwitches by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/assets/fixture_toggles.py")
}
val verifyWarpGunArt by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/assets/test_warp_gun_revision.py")
}
tasks.check { dependsOn(verifyFixtureSwitches, verifyWarpGunArt) }
val verifyDiagnosticReports by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/diagnostics/test_check_reports.py")
}
tasks.check { dependsOn(verifyDiagnosticReports) }
val verifyResonantRouting by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.hexvane.strangematter.machine.ResonantNetworkVerification"
    enableAssertions = true
}
tasks.check { dependsOn(verifyResonantRouting) }
val verifyFloorPanel by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/assets/test_floor_panel.py")
}
tasks.check { dependsOn(verifyFloorPanel) }
val verifyOptionalBeacon by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.hexvane.strangematter.telemetry.BeaconVerification"
    enableAssertions = true
}
tasks.check { dependsOn(verifyOptionalBeacon) }
val verifyRecipeAssets by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_recipes.py")
}
val verifyUiAssets by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/test_validate_ui.py")
}
val verifySharedUi by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/test_shared_ui.py")
}
val verifyMachineSettingsUi by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_machine_settings_ui.py")
}
tasks.check { dependsOn(verifyMachineSettingsUi) }
tasks.check { dependsOn(verifySharedUi) }
val verifyLaboratoryUi by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_laboratory_ui.py")
}
val verifyCognitionGlyphs by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_cognition_glyphs.py")
}
val verifyResearchTreeLayout by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.hexvane.strangematter.research.ResearchTreeLayoutVerification"
    enableAssertions = true
}
val verifyTabletUi by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_tablet_ui.py")
}
val verifyDisciplineIcons by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_discipline_icons.py")
}
val verifyEffects by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_effects.py")
}
val verifyPresentation by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/test_validate_presentation.py")
}
val verifyMachineWork by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_machine_work.py")
}
val verifyHeldLights by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/assets/test_held_light_fix.py")
}
val verifyResonatorGrip by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/assets/test_resonator_grip.py")
}
val verifyTabletGrip by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/assets/test_tablet_grip.py")
}
val verifyEnergeticPresentation by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_energetic_presentation.py")
}
val verifyClientAnimations by tasks.registering(Exec::class) {
    commandLine("pwsh", "-NoProfile", "-File", "tools/validate_blockyanim_client.ps1")
}
val verifyGrassStatusIcon by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_grass_status_icon.py")
}
val verifyThoughtwellEchoes by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_thoughtwell_echoes.py")
}
val verifyThoughtwellHallucinations by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_thoughtwell_hallucinations.py")
}
val verifySeamlessTerrain by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_seamless_terrain.py")
}
val verifyWarpBolts by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_warp_bolts.py")
}
val verifyPluginIdentity by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "-m", "unittest", "discover", "-s", "tools", "-p", "test_migrate_plugin_identity.py")
}
val verifyArchitectureSet by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/assets/validate_architecture_set.py")
}
val verifyFurnitureSet by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/test_validate_furniture_set.py")
}
val verifyRealityForge by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_reality_forge.py")
}
val verifyNullifierPresentation by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/test_validate_nullifier.py")
}
val verifyNullifierContent by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/validate_nullifier_content.py")
}
val verifyWallSupport by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/test_block_support.py")
}
val verifyTexturePacking by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/assets/test_verify_texture_repack.py")
}
val verifyModelAtlases by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/test_validate_model_atlases.py")
}
tasks.check { dependsOn(verifyTexturePacking, verifyModelAtlases) }
val verifyAutomationArt by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/test_automation_art.py")
}
val verifyMachineInventories by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/test_machine_inventory_panel.py")
}
tasks.check { dependsOn(verifyAutomationArt, verifyMachineInventories) }
val verifyFactoryPickup by tasks.registering(Exec::class) {
    commandLine(providers.gradleProperty("pythonExecutable").orElse("python").get(), "tools/test_factory_pickup.py")
}
tasks.check { dependsOn(verifyFactoryPickup) }
tasks.check { dependsOn(verifyArchitectureSet, verifyFurnitureSet, verifyResonatorGrip, verifyTabletGrip, verifyRealityForge, verifyNullifierPresentation, verifyNullifierContent, verifyWallSupport) }
tasks.check { dependsOn(verifyGameplay, verifyRecipeAssets, verifyUiAssets, verifyLaboratoryUi, verifyCognitionGlyphs, verifyResearchTreeLayout, verifyTabletUi, verifyDisciplineIcons, verifyEffects, verifyPresentation, verifyMachineWork, verifyHeldLights, verifyEnergeticPresentation, verifyClientAnimations, verifyGrassStatusIcon, verifyThoughtwellEchoes, verifyThoughtwellHallucinations, verifySeamlessTerrain, verifyWarpBolts, verifyPluginIdentity) }
// Match Aetherhaven: the editor changes build/resources/main during a development
// run; copy those edits back only after the server exits. This is Copy, not Sync:
// generated output must never delete source assets or overwrite manifest metadata.
val syncAssets by tasks.registering(Copy::class) {
    group = "hytale"
    description = "Copy asset-editor changes from the development run back into source resources."
    from(layout.buildDirectory.dir("resources/main"))
    into(layout.projectDirectory.dir("src/main/resources"))
    exclude("manifest.json", "subplugin-packs/**")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

val prepareDevRun by tasks.registering {
    group = "hytale"
    description = "Preserve old jar-based development installs outside the active mods directory."
    doLast {
        val modDir = file("run/mods").toPath().toAbsolutePath().normalize()
        val backupDir = file("run/legacy-mods").toPath().toAbsolutePath().normalize()
        val runRoot = file("run").toPath().toAbsolutePath().normalize()
        check(modDir.startsWith(runRoot) && backupDir.startsWith(runRoot))
        modDir.toFile().listFiles { f -> f.isFile && f.name.matches(Regex("StrangeMatter-[0-9].*\\.jar")) }?.forEach { old ->
            Files.createDirectories(backupDir)
            Files.move(old.toPath(), backupDir.resolve("${System.currentTimeMillis()}-${old.name}"))
            logger.lifecycle("Preserved old development install: ${old.name} in run/legacy-mods")
        }
    }
}

afterEvaluate {
    val server = tasks.getByName("runServer") as JavaExec
    server.dependsOn(tasks.classes, prepareDevRun)
    server.jvmArgs = server.jvmArgs.orEmpty().filter { it.isNotBlank() }
    server.finalizedBy(syncAssets)
    tasks.register<JavaExec>("runServerNoSync") {
        group = "hytale"
        description = "Run the same development server without copying editor changes back to source."
        dependsOn(tasks.classes, prepareDevRun)
        classpath = server.classpath
        mainClass.set(server.mainClass)
        mainModule.set(server.mainModule)
        modularity.inferModulePath.set(server.modularity.inferModulePath)
        javaLauncher.set(server.javaLauncher)
        jvmArgs = server.jvmArgs.orEmpty()
        args = server.args.orEmpty()
        workingDir = server.workingDir
        systemProperties(server.systemProperties)
        environment(server.environment)
        standardInput = System.`in`
        enableAssertions = server.enableAssertions
    }
}
