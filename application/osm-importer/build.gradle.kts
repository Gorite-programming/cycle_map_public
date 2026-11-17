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

// 既存 graph/CH を保持して通行ルールを別 idx に追記: :osm-importer:reindexBikeInfo -Pgraph=<.graph> -Pindex=<.graph.idx> -Ppbf=<.pbf> -Pout=<new.idx> [-Pref=2] [-Psidecar=<out.bikerules>]
tasks.register<JavaExec>("reindexBikeInfo") {
    group = "application"
    description = "Append bicycle rules to a new graph index without rebuilding graph/CH"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gorite.cyclemap.importer.BikeInfoReindexMain")
    jvmArgs("-Xmx4g")
    doFirst {
        args(listOf("graph", "index", "pbf", "out").map {
            project.findProperty(it) as String? ?: error("-P$it is required")
        })
        (project.findProperty("ref") as String?)?.let { args("--ref", it) }
        (project.findProperty("sidecar") as String?)?.let { args("--sidecar", it) }
    }
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
