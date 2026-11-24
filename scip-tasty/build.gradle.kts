plugins {
    id("scip.scala-library")
    id("scip.maven-publish")
}

description = "Reads Scala 3 TASTy and mints SCIP symbols from it"

dependencies {
    implementation(project(":scip-shared"))
    implementation(libs.tasty.query)

    // The parity harness compiles `src/test/resources/parity` with the real Scala 3
    // compiler, so that one invocation produces `.tasty` and `.semanticdb` side by side.
    // That keeps it hermetic: no sbt, which is deliberately absent from CI, Docker and the
    // nix devShell. `scip-semanticdb` supplies the SemanticDB protobuf to read back.
    testImplementation(project(":scip-semanticdb"))
    testImplementation(libs.scala3.compiler)
}
