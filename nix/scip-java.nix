{
  lib,
  stdenv,
  bash,
  coreutils,
  findutils,
  gnused,
  gradle,
  jdk,
  makeWrapper,
}:

stdenv.mkDerivation (finalAttrs: {
  pname = "scip-java";
  # Mirrors the Gradle build's fallback for the `releaseVersion` property,
  # which is passed through below so both agree.
  version = "0.0.0-SNAPSHOT";

  src = lib.cleanSourceWith {
    name = "scip-java-source";
    src = lib.cleanSource ../.;
    # Gradle and Maven outputs from a local build would otherwise be copied
    # into the store and confuse the build in the sandbox.
    filter =
      path: type:
      let
        base = baseNameOf path;
      in
      !(
        type == "directory"
        && (base == "build" || base == "target" || base == ".gradle" || base == ".direnv")
      );
  };

  nativeBuildInputs = [
    gradle
    makeWrapper
  ];

  # Dependencies Gradle downloads during the build, pinned in `deps.json`.
  # Regenerate it with `nix run .#scip-java.updateScript` after changing
  # `gradle/libs.versions.toml` or a build script.
  mitmCache = gradle.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./deps.json;
  };

  gradleFlags = [ "-PreleaseVersion=${finalAttrs.version}" ];

  gradleBuildTask = ":scip-java:installDist";

  # Record the lockfile from the build task itself. The `nixDownloadDeps` task
  # that nixpkgs would use by default reads `project.configurations` from a task
  # action, which Gradle 9 no longer allows, and running the real task captures
  # exactly the dependencies the build needs anyway.
  gradleUpdateTask = finalAttrs.gradleBuildTask;

  # The test suite shells out to Gradle, Maven and sbt against fixture
  # projects that resolve their own dependencies, which the sandbox has no
  # network for. Run `gradle :scip-java:test` in the devShell instead.
  doCheck = false;

  installPhase = ''
    runHook preInstall

    mkdir -p $out/share/scip-java
    cp -r scip-java/build/install/scip-java/. $out/share/scip-java/
    rm $out/share/scip-java/bin/scip-java.bat

    # scip-java needs a full JDK rather than a JRE at run time: it indexes by
    # running the build tool, which invokes scip-java's `javac` wrapper, and
    # that shells out to `javac` from PATH. Prefixed, so a codebase is always
    # indexed with the JDK this package was built against.
    #
    # The shell utilities are only a fallback for an environment that does not
    # provide them, so they go last: bash runs the `javac` wrapper, and Gradle's
    # generated start script calls `ls`, `sed`, `tr`, `uname` and `xargs`.
    makeWrapper $out/share/scip-java/bin/scip-java $out/bin/scip-java \
      --set JAVA_HOME ${jdk} \
      --prefix PATH : ${lib.makeBinPath [ jdk ]} \
      --suffix PATH : ${
        lib.makeBinPath [
          bash
          coreutils
          findutils
          gnused
        ]
      }

    runHook postInstall
  '';

  passthru.updateScript = finalAttrs.mitmCache.updateScript;

  meta = {
    description = "Java and Kotlin indexer for SCIP";
    homepage = "https://github.com/scip-code/scip-java";
    license = lib.licenses.asl20;
    mainProgram = "scip-java";
    platforms = lib.platforms.unix;
  };
})
