import com.modrinth.minotaur.dependencies.ModDependency
import net.darkhax.curseforgegradle.TaskPublishCurseForge
import net.fabricmc.loom.api.mappings.layered.MappingContext
import net.fabricmc.loom.api.mappings.layered.MappingLayer
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace
import net.fabricmc.loom.api.mappings.layered.spec.MappingsSpec
import net.fabricmc.loom.configuration.providers.mappings.intermediary.IntermediaryMappingLayer
import net.fabricmc.loom.configuration.providers.mappings.utils.DstNameFilterMappingVisitor
import net.fabricmc.loom.util.download.DownloadException
import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingDstNsReorder
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.proguard.ProGuardFileReader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

plugins {
	id("fabric-loom").version("1.7.+")
	id("dev.yumi.gradle.licenser").version("1.1.+")
	`java-library`
	`maven-publish`
	id("com.github.johnrengelman.shadow").version("8.1.1")
	id("com.modrinth.minotaur").version("2.+")
	id("net.darkhax.curseforgegradle").version("1.1.+")
}

group = project.property("maven_group") as String
base.archivesName.set(project.property("archives_base_name") as String)
val minecraftVersion = libs.versions.minecraft.get()
val modVersion = project.property("mod_version") as String
version = "${modVersion}+${minecraftVersion}"

// This field defines the Java version your mod target.
val targetJavaVersion = 21

if (!(System.getenv("CURSEFORGE_TOKEN") != null || System.getenv("MODRINTH_TOKEN") != null || System.getenv("LDL_MAVEN") != null)) {
	version = (version as String) + "-local"
}
logger.lifecycle("Preparing version ${version}...")

fun isMCVersionNonRelease(): Boolean {
	return minecraftVersion.matches(Regex("^\\d\\dw\\d\\d[a-z]$"))
			|| minecraftVersion.matches(Regex("\\d+\\.\\d+-(pre|rc)(\\d+)"))
}

fun getMCVersionString(): String {
	if (isMCVersionNonRelease()) {
		return minecraftVersion
	}
	val version = minecraftVersion.split("\\.".toRegex())
	return version[0] + "." + version[1]
}

fun getVersionType(): String {
	return if (isMCVersionNonRelease() || "-alpha." in modVersion) {
		"alpha"
	} else if ("-beta." in modVersion) {
		"beta"
	} else {
		"release"
	}
}

fun parseReadme(): String {
	val excludeRegex = "(?m)<!-- modrinth_exclude\\.start -->(.|\n)*?<!-- modrinth_exclude\\.end -->"
	val linkRegex = "!\\[([A-z_ ]+)]\\((images\\/[A-z.\\/_]+)\\)"

	var readme = file("README.md").readText()
	readme = readme.replace(excludeRegex.toRegex(), "")
	readme = readme.replace(linkRegex.toRegex(), "![\$1](https://raw.githubusercontent.com/LambdAurora/LambDynamicLights/1.19/\$2)")
	return readme
}

fun fetchChangelog(): String? {
	val changelogText = file("CHANGELOG.md").readText()
	val regexVersion = modVersion.replace("\\.".toRegex(), "\\.").replace("\\+".toRegex(), "\\+")
	val changelogRegex = "###? ${regexVersion}\\n\\n(( *- .+\\n)+)".toRegex()
	val matcher = changelogRegex.find(changelogText)

	if (matcher != null) {
		var changelogContent = matcher.groupValues[1]

		val changelogLines = changelogText.split("\n")
		val linkRefRegex = "^\\[([A-z\\d _\\-/+.]+)]: ".toRegex()
		for (i in changelogLines.size - 1 downTo 0) {
			val line = changelogLines[i]
			if (line matches linkRefRegex)
				changelogContent += "\n" + line
			else break
		}
		return changelogContent
	} else {
		return null;
	}
}

configurations["api"].extendsFrom(configurations["shadow"])

repositories {
	mavenLocal()
	mavenCentral()
	maven {
		name = "Terraformers"
		url = uri("https://maven.terraformersmc.com/releases/")
	}
	maven {
		name = "Gegy"
		url = uri("https://maven.gegy.dev")
	}
	maven {
		name = "grondag"
		url = uri("https://maven.dblsaiko.net/")
	}
	maven {
		name = "ParchmentMC"
		url = uri("https://maven.parchmentmc.org")
	}
	exclusiveContent {
		forRepository {
			maven {
				name = "Modrinth"
				url = uri("https://api.modrinth.com/maven")
			}
		}
		filter {
			includeGroup("maven.modrinth")
		}
	}
}

loom {
	accessWidenerPath = file("src/main/resources/lambdynlights.accesswidener")
	runtimeOnlyLog4j = true
}

// Based off Loom, this is required as the releases at the time of writing this buildscript have
// a flaw with the mapping layering preventing the usage of the usual MojangMappingLayer.
@Suppress("UnstableApiUsage")
internal data class MojangMappingLayer(
	val clientMappings: Path, val serverMappings: Path, val nameSyntheticMembers: Boolean,
	val intermediaryMappings: MemoryMappingTree, val logger: Logger
) : MappingLayer {
	@Throws(IOException::class)
	override fun visit(mappingVisitor: MappingVisitor) {
		val mojmap = MemoryMappingTree()

		// Filter out field names matching the pattern
		val nameFilter = DstNameFilterMappingVisitor(mojmap, SYNTHETIC_NAME_PATTERN)

		// Make official the source namespace
		val nsSwitch = MappingSourceNsSwitch(if (nameSyntheticMembers) mojmap else nameFilter, MappingsNamespace.OFFICIAL.toString())

		Files.newBufferedReader(clientMappings).use { clientBufferedReader ->
			Files.newBufferedReader(serverMappings).use { serverBufferedReader ->
				ProGuardFileReader.read(
					clientBufferedReader,
					MappingsNamespace.NAMED.toString(),
					MappingsNamespace.OFFICIAL.toString(),
					nsSwitch
				)
				ProGuardFileReader.read(
					serverBufferedReader,
					MappingsNamespace.NAMED.toString(),
					MappingsNamespace.OFFICIAL.toString(),
					nsSwitch
				)
			}
		}

		intermediaryMappings.accept(MappingDstNsReorder(mojmap, MappingsNamespace.INTERMEDIARY.toString()))

		val switch = MappingSourceNsSwitch(MappingDstNsReorder(mappingVisitor, MappingsNamespace.NAMED.toString()), MappingsNamespace.INTERMEDIARY.toString(), true)
		mojmap.accept(switch)
	}

	override fun getSourceNamespace(): MappingsNamespace {
		return MappingsNamespace.INTERMEDIARY
	}

	override fun dependsOn(): List<Class<out MappingLayer?>> {
		return listOf(IntermediaryMappingLayer::class.java)
	}

	companion object {
		private val SYNTHETIC_NAME_PATTERN: Pattern = Pattern.compile("^(access|this|val\\\$this|lambda\\$.*)\\$[0-9]+$")
	}
}

@Suppress("UnstableApiUsage")
internal data class MojangMappingsSpec(val nameSyntheticMembers: Boolean) : MappingsSpec<MojangMappingLayer?> {
	override fun createLayer(context: MappingContext): MojangMappingLayer {
		val versionInfo = context.minecraftProvider().versionInfo
		val clientDownload = versionInfo.download(MANIFEST_CLIENT_MAPPINGS)
		val serverDownload = versionInfo.download(MANIFEST_SERVER_MAPPINGS)

		if (clientDownload == null) {
			throw RuntimeException("Failed to find official mojang mappings for " + context.minecraftVersion())
		}

		val clientMappings = context.workingDirectory("mojang").resolve("client.txt")
		val serverMappings = context.workingDirectory("mojang").resolve("server.txt")

		try {
			context.download(clientDownload.url())
				.sha1(clientDownload.sha1())
				.downloadPath(clientMappings)

			context.download(serverDownload.url())
				.sha1(serverDownload.sha1())
				.downloadPath(serverMappings)
		} catch (e: DownloadException) {
			throw UncheckedIOException("Failed to download mappings", e)
		}

		return MojangMappingLayer(
			clientMappings,
			serverMappings,
			nameSyntheticMembers,
			context.intermediaryTree().get(),
			context.logger
		)
	}

	companion object {
		// Keys in dependency manifest
		private const val MANIFEST_CLIENT_MAPPINGS = "client_mappings"
		private const val MANIFEST_SERVER_MAPPINGS = "server_mappings"
	}
}

dependencies {
	minecraft(libs.minecraft)
	@Suppress("UnstableApiUsage")
	mappings(loom.layered {
		addLayer(MojangMappingsSpec(false))
		parchment("org.parchmentmc.data:parchment-$minecraftVersion:${libs.versions.mappings.parchment.get()}@zip")
		mappings("dev.lambdaurora:yalmm:$minecraftVersion+build.${libs.versions.mappings.yalmm.get()}")
	})
	modImplementation(libs.fabric.loader)

	modImplementation(libs.fabric.api)

	modImplementation(libs.spruceui)
	include(libs.spruceui)
	modImplementation(libs.pridelib)
	include(libs.pridelib)

	modImplementation(libs.modmenu) {
		this.isTransitive = false
	}

	modRuntimeOnly(libs.sodium)

	shadow(libs.nightconfig.core)
	shadow(libs.nightconfig.toml)
}

java {
	sourceCompatibility = JavaVersion.toVersion(targetJavaVersion)
	targetCompatibility = JavaVersion.toVersion(targetJavaVersion)

	withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"

	options.release.set(targetJavaVersion)
}

tasks.processResources {
	inputs.property("version", project.version)

	filesMatching("fabric.mod.json") {
		expand("version" to project.version)
	}
}

tasks.jar {
	from("LICENSE") {
		rename { "${it}_${base.archivesName.get()}" }
	}
}

license {
	rule(file("HEADER"))
}

tasks.shadowJar {
	dependsOn(tasks.jar)
	configurations = listOf(project.configurations["shadow"])
	destinationDirectory.set(file("${project.layout.buildDirectory.get()}/devlibs"))
	archiveClassifier.set("dev")

	relocate("com.electronwill.nightconfig", "dev.lambdaurora.lambdynlights.shadow.nightconfig")
}

tasks.remapJar {
	dependsOn(tasks.shadowJar)
}

modrinth {
	projectId = project.property("modrinth_id") as String
	versionName = "LambDynamicLights $modVersion (${minecraftVersion})"
	uploadFile.set(tasks.remapJar.get())
	loaders.set(listOf("fabric", "quilt"))
	gameVersions.set(listOf(minecraftVersion))
	versionType.set(if (isMCVersionNonRelease()) "beta" else "release")
	syncBodyFrom.set(parseReadme())
	dependencies.set(
		listOf(
			ModDependency("P7dR8mSH", "required")
		)
	)

	// Changelog fetching
	val changelogContent = fetchChangelog()

	if (changelogContent != null) {
		changelog.set(changelogContent)
	} else {
		afterEvaluate {
			tasks.modrinth.get().setEnabled(false)
		}
	}
}

tasks.modrinth {
	dependsOn(tasks.modrinthSyncBody)
}

tasks.register("curseforge", TaskPublishCurseForge::class) {
	this.setGroup("publishing")

	val token = System.getenv("CURSEFORGE_TOKEN")
	if (token != null) {
		this.apiToken = token
	} else {
		this.isEnabled = false
		return@register
	}

	// Changelog fetching
	var changelogContent = fetchChangelog()

	if (changelogContent != null) {
		changelogContent = "Changelog:\n\n${changelogContent}"
	} else {
		this.isEnabled = false
		return@register
	}

	val mainFile = upload(project.property("curseforge_id"), tasks.remapJar.get())
	mainFile.releaseType = getVersionType()
	mainFile.addGameVersion(minecraftVersion)
	mainFile.addModLoader("Fabric", "Quilt")
	mainFile.addJavaVersion("Java 21", "Java 22")
	mainFile.addEnvironment("Client")

	mainFile.displayName = "LambDynamicLights $modVersion ($minecraftVersion)"
	mainFile.addRequirement("fabric-api")
	mainFile.addOptional("modmenu")
	mainFile.addIncompatibility("optifabric")

	mainFile.changelogType = "markdown"
	mainFile.changelog = changelogContent
}

// Configure the maven publication.
publishing {
	publications {
		create("mavenJava", MavenPublication::class) {
			from(components["java"])

			pom {
				name.set("LambDynamicLights")
				description.set("Adds dynamic lights to Minecraft.")
			}
		}
	}

	repositories {
		mavenLocal()
		maven {
			name = "BuildDirLocal"
			url = uri("${project.layout.buildDirectory.get()}/repo")
		}

		val ldlMaven = System.getenv("LDL_MAVEN")
		if (ldlMaven != null) {
			maven {
				name = "LambDynamicLightsMaven"
				url = uri(ldlMaven)
				credentials {
					username = (project.findProperty("gpr.user") as? String) ?: System.getenv("MAVEN_USERNAME")
					password = (project.findProperty("gpr.key") as? String) ?: System.getenv("MAVEN_PASSWORD")
				}
			}
		}
	}
}