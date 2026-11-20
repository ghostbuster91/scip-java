import com.google.protobuf.gradle.proto

plugins {
    id("scip.java-library")
    alias(libs.plugins.protobuf)
    id("scip.maven-publish")
}

description = "Converts SemanticDB files into SCIP shards"

dependencies {
    api(libs.scip.java.bindings)
    implementation(project(":scip-shared"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.asProvider().get()}"
    }
}

sourceSets {
    named("main") {
        proto {
            srcDir("src/main/protobuf")
        }
    }
}
