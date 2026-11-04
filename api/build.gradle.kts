import java.util.zip.ZipFile

plugins {
    jacoco
    id("java-library")
    id("maven-publish")
}

group = "io.github.exterastuff.dexbundle"

version = "0.1.0"

val artifactType = Attribute.of("artifactType", String::class.java)

abstract class AarToJarTransform : TransformAction<TransformParameters.None> {
    @get:InputArtifact abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val aar = inputArtifact.get().asFile
        val jar = outputs.file("${aar.nameWithoutExtension}.jar")

        ZipFile(aar).use { zip ->
            zip.getInputStream(zip.getEntry("classes.jar")).use { input ->
                jar.outputStream().use { input.copyTo(it) }
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit.junit)

    registerTransform(AarToJarTransform::class) {
        from.attribute(artifactType, "aar")
        to.attribute(artifactType, "jar")
    }

    testImplementation(libs.aliuhook) {
        isTransitive = false
        attributes { attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, "aar")) }
    }

    compileOnly(libs.aliuhook) {
        attributes {
            attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements::class.java, "aar"),
            )
        }
    }

    implementation(libs.annotations)
}

configurations.compileClasspath {
    attributes {
        attribute(artifactType, "jar")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11

    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "api"

            from(components["java"])
        }
    }
}

configurations.testCompileClasspath { attributes { attribute(artifactType, "jar") } }
configurations.testRuntimeClasspath { attributes { attribute(artifactType, "jar") } }

jacoco { toolVersion = "0.8.13" }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports { xml.required.set(true); html.required.set(true) }
}
