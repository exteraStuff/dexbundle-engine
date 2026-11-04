plugins {
    id("java-library")
    id("maven-publish")
}

group = "io.github.exterastuff.dexbundle"

version = "0.1.0"

dependencies {
    implementation(libs.annotations)
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
