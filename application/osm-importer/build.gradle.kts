plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation(project(":routing-core"))
    implementation("org.openstreetmap.osmosis:osmosis-pbf:0.48.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

application {
    mainClass.set("com.gorite.cyclemap.importer.OsmImporterKt")
}

// 経路の実際の登り量を方針別に比べる: ./gradlew :osm-importer:benchmarkAscent -Pgraph=<.graph> -Pdem=<dem14> [-Pod=200]
tasks.register<JavaExec>("benchmarkAscent") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gorite.cyclemap.importer.AscentBenchmarkKt")
    jvmArgs("-Xmx3g")
    doFirst {
        args(
            project.findProperty("graph") as String? ?: error("-Pgraph が必要です"),
            project.findProperty("dem") as String? ?: error("-Pdem が必要です"),
            (project.findProperty("od") ?: "200").toString(),
        )
    }
}
