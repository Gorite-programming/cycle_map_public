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
    jvmArgs("-Xmx${project.findProperty("maxHeap") ?: "2g"}")
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
    jvmArgs("-Xmx${project.findProperty("maxHeap") ?: "2g"}")
    doFirst {
        args(
            project.findProperty("graph") as String? ?: error("-Pgraph が必要です"),
            project.findProperty("out") as String? ?: error("-Pout が必要です"),
            (project.findProperty("od") ?: "200").toString(),
        )
    }
}

// 県間経路の実グラフ計測: ./gradlew :routing-core:crossRouteBench -PgraphList=<A.graph>,<B.graph>,<C.graph> -Pod="lat,lon,lat,lon[;...]"
tasks.register<JavaExec>("crossRouteBench") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gorite.cyclemap.routing.CrossRouteBenchKt")
    jvmArgs("-Xmx${project.findProperty("maxHeap") ?: "2g"}")
    doFirst {
        val od = project.findProperty("od") as String? ?: error("-Pod=lat,lon,lat,lon が必要です")
        val graphList = project.findProperty("graphList") as String?
        if (graphList != null) args(graphList, od)
        else args(
            project.findProperty("graphA") as String? ?: error("-PgraphList または -PgraphA/-PgraphB が必要です"),
            project.findProperty("graphB") as String? ?: error("-PgraphList または -PgraphA/-PgraphB が必要です"),
            od,
        )
    }
}

tasks.register<JavaExec>("buildCh") {
    group = "verification"
    description = "Build an S1 contraction hierarchy sidecar"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gorite.cyclemap.routing.ChBuildMain")
    jvmArgs("-Xmx${project.findProperty("heap") ?: "2g"}")
    doFirst { args(project.findProperty("graph") as String? ?: error("-Pgraph=<.graph> required"), project.findProperty("out") as String? ?: error("-Pout=<.graph.ch> required")) }
}

tasks.register<JavaExec>("benchmarkCh") {
    group = "verification"
    description = "Compare S1 CH against production A* (-Pmmap=true|only also exercises ContractionHierarchy.open)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gorite.cyclemap.routing.ChBenchmarkMain")
    jvmArgs("-Xmx${project.findProperty("heap") ?: "2g"}")
    doFirst { args(project.findProperty("graph") as String? ?: error("-Pgraph=<.graph> required"), project.findProperty("ch") as String? ?: error("-Pch=<.graph.ch> required"), (project.findProperty("od") ?: "1000").toString(), (project.findProperty("seed") ?: "20261108").toString(), (project.findProperty("mmap") ?: "false").toString()) }
}

tasks.register<JavaExec>("benchmarkAttach") {
    group = "verification"
    description = "Compare LazyMappedRoadGraph.route with an attached CH against plain A* (same graph)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gorite.cyclemap.routing.AttachBenchMain")
    jvmArgs("-Xmx${project.findProperty("heap") ?: "2g"}")
    doFirst { args(project.findProperty("graph") as String? ?: error("-Pgraph=<.graph> required"), project.findProperty("ch") as String? ?: error("-Pch=<.graph.ch> required"), (project.findProperty("od") ?: "1000").toString(), (project.findProperty("seed") ?: "20261108").toString()) }
}

// 県またぎ CH vs A* チェーンの突き合わせ: -Pgraphs=A.graph,B.graph[,C.graph] -Pchs=A.graph.ch,B.graph.ch[,...] [-Pod=20] [-Pseed=..] [-Pheap=6g]
tasks.register<JavaExec>("benchmarkChainCh") {
    group = "verification"
    description = "Compare routeAcrossChainCh against the A* chain on real graphs"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gorite.cyclemap.routing.ChainChBenchMain")
    jvmArgs("-Xmx${project.findProperty("heap") ?: "6g"}")
    doFirst {
        args(
            project.findProperty("graphs") as String? ?: error("-Pgraphs=<comma list of .graph> required"),
            project.findProperty("chs") as String? ?: error("-Pchs=<comma list of .graph.ch> required"),
            (project.findProperty("od") ?: "20").toString(), (project.findProperty("seed") ?: "20261108").toString(),
        )
    }
}
