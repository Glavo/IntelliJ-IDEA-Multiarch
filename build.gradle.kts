import de.undercouch.gradle.tasks.download.Download
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.glavo.build.Arch
import org.glavo.build.IJProcessor
import org.glavo.build.tasks.GenerateReadMe
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.exists
import kotlin.io.path.outputStream

plugins {
    id("de.undercouch.download") version "5.6.0"
}

group = "org.glavo"
version = property("idea.version") as String

val downloadDir = layout.buildDirectory.dir("download").get()
val baseArch = Arch.AARCH64
val baseArchName = baseArch.normalize()
val ijProductCode = property("idea.product_code") as String
val ijDir = downloadDir.dir("idea$ijProductCode-$version-$baseArchName")

var downloadIJ = tasks.create<Download>("downloadIJ") {
    src("https://download.jetbrains.com/idea/idea$ijProductCode-$version-$baseArchName.tar.gz")
    dest(downloadDir)
    overwrite(false)
}

val ijTar: Path
    get() = downloadIJ.outputFiles.first().toPath()

inline fun openTarInputStream(file: Path, action: (TarArchiveInputStream) -> Unit) {
    Files.newInputStream(file).use { rawInput ->
        GZIPInputStream(rawInput).use { gzipInput ->
            TarArchiveInputStream(gzipInput).use(action)
        }
    }
}

tasks.create("extractIJ") {
    dependsOn(downloadIJ)
    outputs.dir(ijDir)

    doLast {
        if (System.getProperty("os.name").lowercase().contains("windows")) {
            throw GradleException("This task should not run on Windows")
        }

        val targetDir = ijDir.asFile.toPath()
        targetDir.toFile().deleteRecursively()
        Files.createDirectories(targetDir)

        openTarInputStream(ijTar) { tar ->
            val prefix = tar.nextEntry.let {
                if (it == null || !it.isDirectory || it.name.count { ch -> ch == '/' } != 1) {
                    throw GradleException("Invalid directory entry: ${it.name}")
                }

                it.name
            }

            do {
                val entry = tar.nextEntry ?: break

                entry.apply {
                    logger.info("Extracting $name (size=$size isDirectory=$isDirectory isSymbolicLink=$isSymbolicLink)")
                }

                if (!entry.name.startsWith(prefix)) {
                    throw GradleException("Invalid entry: ${entry.name}")
                }

                if (entry.isLink) {
                    throw GradleException("Unable handle link: ${entry.name}")
                }

                val targetName = entry.name.substring(prefix.length)
                if (targetName.isEmpty())
                    continue

                val target = targetDir.resolve(targetName)

                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    if (target.exists()) {
                        throw GradleException("Duplicate entry ${entry.name}")
                    }
                    if (entry.isSymbolicLink) {
                        Files.createSymbolicLink(target, Path.of(entry.linkName))
                    } else {
                        target.outputStream().use { tar.copyTo(it) }
                    }
                }
            } while (true)
        }
    }
}

val targetDir = layout.buildDirectory.dir("target").get()

val arches = listOf(Arch.RISCV64, Arch.LOONGARCH64)

for (arch in arches) {
    val downloadJRE = findProperty("idea.jdk.linux.${arch.normalize()}.url")?.let { url ->
        tasks.create<Download>("downloadJREFor${arch.normalize()}") {
            src(url)
            dest(downloadDir)
            overwrite(false)
        }
    }

    tasks.create("createFor$arch") {
        dependsOn(downloadIJ)

        val jreFile = downloadJRE?.let {
            dependsOn(it)
            it.outputFiles.first().toPath()
        }

        val versionAdditional = project.property("idea.version.additional") as String
        val nativesZip = layout.projectDirectory.dir("resources").file("natives-linux-${arch.normalize()}.zip")
        val output = targetDir.file("idea$ijProductCode-$version+$versionAdditional-${arch.normalize()}.tar.gz")

        inputs.files(ijTar, nativesZip)
        outputs.file(output)

        if (jreFile != null) {
            inputs.file(jreFile)
        }

        doLast {
            IJProcessor(
                this,
                baseArch, ijProductCode, ijTar,
                arch, nativesZip.asFile.toPath(), jreFile,
                output.asFile.toPath()
            ).use { it.process() }
        }
    }
}

tasks.create<GenerateReadMe>("generateReadMe") {
    templateFile.set(project.file("README.md.template"))
    propertiesFile.set(project.file("README.properties"))
    outputFile.set(project.file("README.md"))
}
