// Stonecutter 多版本共享构建脚本：每个 versions/<版本> 子项目都用它构建，
// stonecutter.current.version 是当前目标版本；版本专属数值放在 versions/<版本>/gradle.properties。
// 全部版本统一使用官方 mojang 映射（决策点 D1），共享一棵 src/ 源码树；
// 1.19.4/1.20.1 的属性注入走 Statement（决策点 D2），其余版本用自带注入器。
plugins {
    id("fabric-loom") version "1.19.3"
}

val mcVersion = stonecutter.current?.version ?: error("stonecutter.current is not available for this project")
val javaRelease = (property("java_release") as String).toInt()

// 1.19.4 / 1.20.1 走 Statement 路线（属性追加交给 Statement）；1.21.1+ 用自带的状态注入器。
val statementEra = mcVersion == "1.19.4" || mcVersion == "1.20.1"
// 26.3 游戏本体不再混淆：依赖按 Fabric 迁移指南用普通 implementation（versions/26.3 已开 disableObfuscation）。
val unobfuscatedEra = mcVersion == "26.3"

version = "${property("mod_version")}+$mcVersion"
group = property("maven_group") as String
base { archivesName = property("archives_base_name") as String }

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://jitpack.io")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    if (unobfuscatedEra) {
        // disableObfuscation 下 Loom 不创建 mod* remap 配置：26.x 游戏与模组都是官方名，无需重映射，
        // 依赖用普通 implementation（fabric loader 在开发运行时通过 classpath 发现 mod）。
        add("implementation", "net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
        add("implementation", "net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
    } else {
        add("mappings", loom.officialMojangMappings())
        add("modImplementation", "net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
        add("modImplementation", "net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")
        if (statementEra) {
            // Statement 4.2.9 单 jar 覆盖 1.14.4-1.20.4，1.19.4 / 1.20.1 共用。
            add("modApi", "com.github.Virtuoel:Statement:4.2.9") {
                exclude(group = "net.fabricmc.fabric-api")
            }
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)
    // depends 里的 loader 下限与 mixins 列表都会参与 fabric.mod.json 生成，必须声明为输入，
    // 否则改了这些值 `processResources` 仍会被判为 UP-TO-DATE，jar 里还是旧声明。
    inputs.property("fabricLoaderMin", project.findProperty("fabric_loader_min") ?: project.property("fabric_loader_version"))
    inputs.property("statementEra", statementEra)
    inputs.property("unobfuscatedEra", unobfuscatedEra)
    filteringCharset = "UTF-8"
    // 只做占位符替换。不要用 Gradle expand：它会把 JSON 字符串里的 \n 解释成真实换行，
    // 造成源文件合法、最终 JAR 里的 fabric.mod.json 却损坏。
    val modId = project.property("mod_id") as String
    val modName = project.property("mod_name") as String
    val modAuthor = project.property("mod_author") as String
    val modVersion = project.version.toString()
    val modDescription = project.property("mod_description") as String
    val modLicense = project.property("mod_license") as String
    val loaderVersion = project.property("fabric_loader_version") as String
    // depends 里声明的是"实测最小可运行 loader"，不是构建用的 loader（构建用 0.20.1 只是工具链）。
    // 每个版本的下限 = max(Fabric API 自身要求, 0.15.0)：
    //   0.15.0 起 loader 自带 MixinExtras，CacheShapeGuardMixin 用到 @ModifyExpressionValue。
    val loaderMin = (project.findProperty("fabric_loader_min") as String?) ?: loaderVersion
    val mcDependency = project.property("mc_dependency") as String
    val statementDepends = if (statementEra) ",\"statement\": \"*\"" else ""
    val extraMixins = if (!statementEra) ",\"liquidphase.states.mixins.json\"" else ""
    val extraMixins26 = if (unobfuscatedEra) ",\"liquidphase.26.mixins.json\"" else ""
    val extraMixinsWire = if (unobfuscatedEra) "" else ",\"liquidphase.wire.mixins.json\""
    // 渲染层 mixin 只在 1.19.4–1.21.11 需要（26.3 由贴图透明度自动推导图层）。
    val extraMixinsLayer = if (unobfuscatedEra) "" else ",\"liquidphase.layer.mixins.json\""
    filesMatching("fabric.mod.json") {
        filter { line -> line
            .replace("\${version}", modVersion)
            .replace("\${mod_id}", modId)
            .replace("\${mod_name}", modName)
            .replace("\${mod_author}", modAuthor)
            .replace("\${mod_description}", modDescription)
            .replace("\${mod_license}", modLicense)
            .replace("\${fabric_loader_version}", loaderMin)
            .replace("\${mc_dependency}", mcDependency)
            .replace("\${statement_depends}", statementDepends)
            .replace("\${extra_mixins}", extraMixins)
            .replace("\${extra_mixins_26}", extraMixins26)
            .replace("\${extra_mixins_wire}", extraMixinsWire)
            .replace("\${extra_mixins_layer}", extraMixinsLayer)
        }
    }
}

// ---- 相覆盖 blockstate 生成（规划 §3.6 方案 a / 决策点 D11）----
// 从 Loom 缓存（GRADLE_USER_HOME/caches/fabric-loom）的客户端 jar 提取宿主 blockstate，
// 转 multipart 并追加 phase 覆盖层。依赖 python3（AGENTS.md 有环境说明）；产物进 processResources。
val phaseBlocksFile = rootDir.resolve("tools/phase-blocks/${mcVersion}.ids")
val blockstateOutDir = layout.buildDirectory.dir("liquidphase-blockstates")

val convertBlockstates = tasks.register<Exec>("convertPhaseBlockstates") {
    group = "liquidphase"
    description = "Extracts vanilla blockstates for phase-capable blocks and appends phase overlay parts."
    onlyIf { phaseBlocksFile.exists() }
    inputs.file(phaseBlocksFile)
    inputs.file(rootDir.resolve("tools/gen_phase_blockstates.py"))
    inputs.file(rootDir.resolve("tools/redstone-hosts.txt"))
    outputs.dir(blockstateOutDir)
    doFirst {
        val loomCache = gradle.gradleUserHomeDir.resolve("caches/fabric-loom/${mcVersion}")
        val jar = loomCache.walkTopDown()
            .filter { it.name == "minecraft-client.jar" }
            .firstOrNull()
            ?: throw GradleException("minecraft-client.jar not found in fabric-loom cache for ${mcVersion}; run any build task once to download it")
        commandLine(
            "python",
            rootDir.resolve("tools/gen_phase_blockstates.py").absolutePath,
            jar.absolutePath,
            phaseBlocksFile.absolutePath,
            blockstateOutDir.get().asFile.absolutePath,
            rootDir.resolve("tools/redstone-hosts.txt").absolutePath
        )
    }
}

sourceSets.main {
    resources.srcDir(blockstateOutDir)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(convertBlockstates)
}
// 生成的 blockstate 只属于运行时资源；为 jar/sourcesJar 声明显式依赖（sourcesJar 可能尚未注册）。
tasks.matching { it.name == "sourcesJar" || it.name == "jar" }.configureEach {
    dependsOn(convertBlockstates)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaRelease
}

java {
    // 不固定 toolchain：编译用 options.release 切 17/21，运行构建的 JVM 由环境提供（>=21 即可）。
    sourceCompatibility = JavaVersion.toVersion(javaRelease)
    targetCompatibility = JavaVersion.toVersion(javaRelease)
    withSourcesJar()
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${base.archivesName.get()}" }
    }
}
