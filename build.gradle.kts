import com.github.jengelman.gradle.plugins.shadow.ShadowJavaPlugin
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL

plugins {
    application
    kotlin("jvm") version "2.1.21"
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gradleup.shadow") version "8.3.6"
}

var mainClassName: String by application.mainClass
mainClassName = "com.dfsek.terra.biometool.BiomeToolLauncher"

group = "com.dfsek"
version = "0.5.1"

val runDir = file("$buildDir/run")

repositories {
    mavenLocal()  // Check local ~/.m2 first (use publish_to_maven_local.bat in Terra to populate)
    mavenCentral()
    maven {
        name = "Repsy-Terra"
        url = uri("https://repo.repsy.io/mvn/diytechy/terra")
    }
    maven {
        name = "Repsy-DendryTerra"
        url = uri("https://repo.repsy.io/mvn/diytechy/dendryterra")
    }
    maven {
        name = "Solo Studios"
        url = uri("https://maven.solo-studios.ca/releases")
    }
    maven {
        name = "CodeMC"
        url = uri("https://repo.codemc.org/repository/maven-public/")
    }
    maven {
        name = "Jitpack"
        url = uri("https://jitpack.io")
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}

val javafxModules = listOf(
    "base",
    "controls",
    // "fxml",
    "graphics",
    "media",
    // "swing",
    // "web",
)

javafx {
    version = "21.0.2"
    modules = javafxModules.map { "javafx.$it" }
}

val shadow: Configuration by configurations.getting

val compileOnly: Configuration by configurations.compileOnly
val compileClasspath: Configuration by configurations.compileClasspath
val implementation: Configuration by configurations.implementation
val runtimeClasspath: Configuration by configurations.runtimeClasspath

val linuxImplementation: Configuration by configurations.creating {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named<Usage>(Usage.JAVA_RUNTIME))
        attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named<OperatingSystemFamily>("linux"))
        attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, objects.named<MachineArchitecture>("x86-64"))
    }
    extendsFrom(implementation)
}

val windowsImplementation: Configuration by configurations.creating {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named<Usage>(Usage.JAVA_RUNTIME))
        attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named<OperatingSystemFamily>("windows"))
        attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, objects.named<MachineArchitecture>("x86-64"))
    }
    extendsFrom(implementation)
}

val osxImplementation: Configuration by configurations.creating {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named<Usage>(Usage.JAVA_RUNTIME))
        attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named<OperatingSystemFamily>("mac"))
        attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, objects.named<MachineArchitecture>("x86-64"))
    }
    extendsFrom(implementation)
}

compileClasspath.extendsFrom(linuxImplementation)
compileClasspath.extendsFrom(windowsImplementation)
compileClasspath.extendsFrom(osxImplementation)

val terraAddon: Configuration by configurations.creating {
    runtimeClasspath.extendsFrom(this)
}
val bootstrapTerraAddon: Configuration by configurations.creating {
    runtimeClasspath.extendsFrom(this)
}

gradle.taskGraph.whenReady {
    val terraModule = configurations.compileClasspath.get().resolvedConfiguration
        .resolvedArtifacts.find { it.moduleVersion.id.group == "com.dfsek.terra" && it.moduleVersion.id.name == "base" }
    if(terraModule != null) {
        val repo = when {
            terraModule.file.path.replace("\\", "/").contains("/.m2/repository/") -> "mavenLocal"
            else -> "remote (Repsy or other)"
        }
        logger.lifecycle("Terra base resolved: ${terraModule.moduleVersion.id} from $repo")
        logger.lifecycle("  -> ${terraModule.file}")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))

    val terraGitHash = "e971aef4a"

    bootstrapTerraAddon("com.dfsek.terra:api-addon-loader:0.1.0-BETA-$terraGitHash")
    bootstrapTerraAddon("com.dfsek.terra:manifest-addon-loader:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:biome-provider-extrusion:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:biome-provider-image:2.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:biome-provider-pipeline:2.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:biome-provider-single:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:biome-query-api:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:chunk-generator-noise-3d:1.2.1-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:command-addons:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:command-packs:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:command-profiler:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:command-structures:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:config-biome:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:config-distributors:1.0.1-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:config-feature:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:config-flora:1.0.1-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:config-locators:1.1.1-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:config-noise-function:1.2.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:config-number-predicate:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:config-ore:1.1.1-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:config-palette:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:config-structure:1.0.1-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:generation-stage-feature:1.1.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:generation-stage-structure:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:language-yaml:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:library-image:1.1.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:locator-slant-noise-3d:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:palette-block-shortcut:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:pipeline-image:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:structure-block-shortcut:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:structure-mutator:0.1.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:structure-sponge-loader:1.0.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:structure-terrascript-loader:1.2.0-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:terrascript-function-check-noise-3d:1.0.1-BETA-$terraGitHash")
    terraAddon("com.dfsek.terra:terrascript-function-sampler:1.0.0-BETA-$terraGitHash")
    terraAddon("com.github.diytechy:dendryterra:1.0.0-BETA-3")


    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("com.dfsek.terra:base:7.0.0-BETA-$terraGitHash")

    implementation("ca.solo-studios:slf4k:0.5.4")

    implementation("ch.qos.logback:logback-classic:1.5.18")

    implementation("com.google.guava:guava:33.4.8-jre")

    // NBT library required by structure-sponge-loader addon
    implementation("com.github.Querz:NBT:6.1")

    implementation("no.tornado:tornadofx:1.7.20") {
        exclude("org.jetbrains.kotlin")
    }

    implementation("commons-io:commons-io:2.19.0")

    for (javafxModule in javafxModules) {
        val mavenCoordinates = "org.openjfx:javafx-$javafxModule:${javafx.version}"

        linuxImplementation("$mavenCoordinates:linux")
        windowsImplementation("$mavenCoordinates:win")
        osxImplementation("$mavenCoordinates:mac")
    }

    // Jansi for terminal colouring on Windows
    windowsImplementation("org.fusesource.jansi:jansi:2.4.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "21"
}

val jar by tasks.jar
val javadoc by tasks.javadoc

tasks.withType<ShadowJar>() {
    if (name == ShadowJavaPlugin.SHADOW_JAR_TASK_NAME)
        return@withType

    group = "Jar"
    description = "A platform jar for $archiveClassifier with runtime dependencies"
    manifest.inheritFrom(jar.manifest)

    from(sourceSets.main.orNull?.output)
    configurations.add(project.configurations.runtimeClasspath.orNull)
    exclude("META-INF/INDEX.LIST", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")

    doFirst {
        archiveVersion.set(project.version.toString())
    }
}

val javadocJar by tasks.creating(Jar::class) {
    archiveClassifier.set("javadoc")
    dependsOn(javadoc)
    from(javadoc.destinationDir)
}

val sourcesJar by tasks.creating(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets["main"].allSource)
}

val shadowJarLinux by tasks.creating(ShadowJar::class) {
    archiveClassifier.set("linux")
    configurations = listOf(linuxImplementation)
}

val shadowJarWin by tasks.creating(ShadowJar::class) {
    archiveClassifier.set("win")
    configurations = listOf(windowsImplementation)
}

val shadowJarOSX by tasks.creating(ShadowJar::class) {
    archiveClassifier.set("osx")
    configurations = listOf(osxImplementation)
}

val shadowJarAll by tasks.creating(ShadowJar::class) {
    archiveClassifier.set("all")
    configurations = listOf(linuxImplementation, windowsImplementation, osxImplementation)
}

tasks.named("shadowDistZip") {
    dependsOn(shadowJarAll)
}

tasks.named("shadowDistTar") {
    dependsOn(shadowJarAll)
}

tasks.named("startShadowScripts") {
    dependsOn(shadowJarAll)
}

tasks.withType<Jar>() {
    entryCompression = ZipEntryCompression.STORED
    manifest {
        attributes(
            "Main-Class" to mainClassName,
            "Built-By" to System.getProperties()["user.name"],
            "Built-Jdk" to System.getProperties()["java.version"],
            "Name" to project.name,
            "Add-Opens" to "javafx.graphics/javafx.scene jdk.unsupported/sun.misc",
        )
    }
}

val downloadDefaultPacks: Task by tasks.creating() {
    group = "application"

    doFirst {
        val defaultPack = URL("https://github.com/PolyhedralDev/TerraOverworldConfig/releases/download/v1.5.1/default.zip")
        val fileName = defaultPack.file.substring(defaultPack.file.lastIndexOf("/"))

        file("$runDir/packs/").mkdirs()

        defaultPack.openStream().transferTo(file("$runDir/packs/$fileName").outputStream())
    }

}

val prepareRunAddons by tasks.creating(Sync::class) {
    group = "application"
    val terraAddonJars = terraAddon.resolvedConfiguration.firstLevelModuleDependencies.flatMap { dependency ->
        dependency.moduleArtifacts.map { it.file }
    }
    val terraBoostrapJars = bootstrapTerraAddon.resolvedConfiguration.firstLevelModuleDependencies.flatMap { dependency ->
        dependency.moduleArtifacts.map { it.file }
    }

    from(terraAddonJars)

    from(terraBoostrapJars) {
        into("bootstrap")
    }

    into("$runDir/addons")
}

val prepareDistAddons by tasks.creating(Sync::class) {
    group = "distribution"
    description = "Copies Terra addons to build/libs for standalone JAR usage"

    val terraAddonJars = terraAddon.resolvedConfiguration.firstLevelModuleDependencies.flatMap { dependency ->
        dependency.moduleArtifacts.map { it.file }
    }
    val terraBoostrapJars = bootstrapTerraAddon.resolvedConfiguration.firstLevelModuleDependencies.flatMap { dependency ->
        dependency.moduleArtifacts.map { it.file }
    }

    from(terraAddonJars)

    from(terraBoostrapJars) {
        into("bootstrap")
    }

    into("$buildDir/libs/addons")
}

tasks.getByName<JavaExec>("run") {
    dependsOn(prepareRunAddons, downloadDefaultPacks)
    runDir.mkdirs()

    workingDir = runDir
    @Suppress("UselessCallOnNotNull") // Thanks Kotlin
    jvmArgs = jvmArgs.orEmpty() + listOf(
        "--add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED",
        "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED"
    )
}

val createLauncherScripts by tasks.creating {
    group = "distribution"
    description = "Creates launcher scripts for standalone JAR execution"

    doLast {
        // Windows batch script
        file("$buildDir/libs/BiomeTool.bat").writeText("""
@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
java --add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED --add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -jar "%SCRIPT_DIR%BiomeTool-${project.version}-win.jar" %*
endlocal
""".trimIndent())

        // Unix shell script
        file("$buildDir/libs/BiomeTool.sh").writeText("""
#!/bin/bash
SCRIPT_DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
java --add-opens=javafx.graphics/javafx.scene=ALL-UNNAMED --add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED -jar "${'$'}SCRIPT_DIR/BiomeTool-${project.version}-linux.jar" "${'$'}@"
""".trimIndent())
    }
}

tasks.build {
    dependsOn(javadocJar, sourcesJar)
    dependsOn(project.tasks.withType<ShadowJar>())
    finalizedBy(prepareDistAddons, createLauncherScripts)
}
