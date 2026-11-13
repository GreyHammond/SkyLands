plugins {
    java
}

group = "dev.skyrealms"
version = "0.12.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.249-stable")
    // Optional at runtime: only used when Floodgate is installed (native Bedrock forms).
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.jar {
    archiveFileName.set("skylands.jar")
    // Ship the license and the attribution terms inside the jar.
    from("LICENSE", "NOTICE")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
