plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// 実グラフのベンチ: ./gradlew :routing-core:benchmarkRealGraph -Pgraph=/path/to/x.graph [-Pod=200] [-Pseed=20261108]
tasks.register<JavaExec>("benchmarkRealGraph") {
    group = "verification"
    description = "実グラフで経路品質を条件別に比較する"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gorite.cyclemap.routing.GraphBenchmarkRunnerKt")
    jvmArgs("-Xmx2g")
    doFirst {
        val graph = project.findProperty("graph") as String? ?: error("-Pgraph=<.graph path> が必要です")
        args(graph, (project.findProperty("od") ?: "200").toString(), (project.findProperty("seed") ?: "20261108").toString())
    }
}

// 経路探索の結果を固定の OD で書き出す (実装の変更前後の比較用): ./gradlew :routing-core:routeDump -Pgraph=<.graph> -Pout=<file> [-Pod=200]
tasks.register<JavaExec>("routeDump") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gorite.cyclemap.routing.RouteDumpKt")
    jvmArgs("-Xmx2g")
    doFirst {
        args(
            project.findProperty("graph") as String? ?: error("-Pgraph が必要です"),
            project.findProperty("out") as String? ?: error("-Pout が必要です"),
            (project.findProperty("od") ?: "200").toString(),
        )
    }
}
