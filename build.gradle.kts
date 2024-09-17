import de.undercouch.gradle.tasks.download.Download
import org.glavo.build.Arch
import org.glavo.build.Product
import org.glavo.build.tasks.ExtractIntelliJ
import org.glavo.build.tasks.GenerateReadMe
import org.glavo.build.tasks.TransformIntelliJ
import java.util.*

plugins {
    id("de.undercouch.download") version "5.6.0"
}

group = "org.glavo"
version = property("idea.version") as String

val downloadDir = layout.buildDirectory.dir("download").get()!!
val configDir = layout.projectDirectory.dir("config")!!
val jbBaseArch = Arch.AARCH64

val Download.outputFile: File
    get() = outputFiles.first()

val arches = listOf(Arch.RISCV64, Arch.LOONGARCH64)
val products = listOf(Product.IDEA_IC, Product.IDEA_IU)

val downloadJDKTasks = arches.associateWith { arch ->
    findProperty("idea.jdk.linux.${arch.normalize()}.url")?.let { url ->
        tasks.create<Download>("downloadJDK-${arch.normalize()}") {
            src(url)
            dest(downloadDir.dir("jdk"))
            overwrite(false)
        }
    }
}

fun loadProperties(propertiesFile: File): Map<String, String> {
    val properties = Properties()
    if (propertiesFile.exists()) {
        propertiesFile.reader().use { reader ->
            properties.load(reader)
        }
    }

    return mutableMapOf<String, String>().also { res ->
        properties.forEach { (key, value) -> res[key.toString()] = value.toString() }
    }
}

val defaultProductPropertiesFile = configDir.dir("product").file("default.properties").asFile!!
val defaultProductProperties = loadProperties(defaultProductPropertiesFile)

for (product in products) {
    val productPropertiesFile = configDir.dir("product").file("$product.properties").asFile
    val productProperties = loadProperties(productPropertiesFile).withDefault { defaultProductProperties[it] }

    val productVersion = productProperties["version"]!!
    val productVersionAdditional = productProperties["version.additional"]!!

    val downloadProductTask = tasks.create<Download>("download${product.productCode}") {
        inputs.files(defaultProductPropertiesFile, productPropertiesFile)

        src(product.getDownloadLink(productVersion, jbBaseArch))
        dest(downloadDir.dir("ide"))
        overwrite(false)
    }

    tasks.create<ExtractIntelliJ>("extract${product.productCode}") {
        dependsOn(downloadProductTask)

        sourceFile.set(downloadProductTask.outputFile)
        targetDir.set(
            downloadProductTask.outputFile.parentFile.resolve(
                product.getFileNameBase(productVersion, jbBaseArch)
            )
        )
    }

    for (targetArch in arches) {
        tasks.create<TransformIntelliJ>("create-${targetArch.normalize()}") {
            dependsOn(downloadProductTask)

            inputs.files(defaultProductPropertiesFile, productPropertiesFile)

            downloadJDKTasks[targetArch]?.let {
                dependsOn(it)
                jreFile.set(it.outputFile)
            }

            baseArch.set(jbBaseArch)
            productCode.set(product.productCode)
            baseTar.set(downloadProductTask.outputFile)

            arch.set(targetArch)
            nativesZipFile.set(
                layout.projectDirectory.dir("resources").file("natives-linux-${targetArch.normalize()}.zip").asFile
            )
            outTar.set(
                layout.buildDirectory.dir("target").get()
                    .file(product.getFileNameBase("$productVersion+$productVersionAdditional", targetArch) + ".tar.gz").asFile
            )
        }
    }
}

tasks.create<GenerateReadMe>("generateReadMe") {
    templateFile.set(project.file("template/README.md.template"))
    propertiesFile.set(project.file("template/README.properties"))
    outputFile.set(project.file("README.md"))
}

