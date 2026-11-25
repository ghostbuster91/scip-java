{
  inputs = {
    flake-utils = {
      url = "github:numtide/flake-utils";
    };
    nixpkgs = {
      url = "github:NixOS/nixpkgs/nixos-26.05";
    };
    scip = {
      url = "github:scip-code/scip";
    };
  };

  outputs =
    {
      self,
      flake-utils,
      nixpkgs,
      scip,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs { inherit system; };
        scipCli = scip.packages.${system}.default;
        mkDevShell =
          jdk:
          pkgs.mkShellNoCC {
            buildInputs = with pkgs; [
              bazelisk
              coursier
              git
              (gradle_9.override ({ java = jdk; }))
              jdk
              jq
              (maven.override ({ jdk_headless = jdk; }))
              nixfmt
              nodejs
              scipCli
              sbt
              yarn
            ];
          };
      in
      {
        checks = {
          actionlint = pkgs.runCommand "check-actionlint" { } ''
            ${pkgs.actionlint}/bin/actionlint ${./.github/workflows}/*.yaml
            touch $out
          '';
          nixfmt = pkgs.runCommand "check-nixfmt" { } ''
            ${pkgs.nixfmt}/bin/nixfmt --check ${./flake.nix}
            touch $out
          '';
          renovate = pkgs.runCommand "check-renovate" { } ''
            LOG_LEVEL=warn ${pkgs.renovate}/bin/renovate-config-validator \
              ${./.github/renovate.json}
            touch $out
          '';
          shellcheck = pkgs.runCommand "check-shellcheck" { } ''
            ${pkgs.shellcheck}/bin/shellcheck ${./bin}/*.sh
            touch $out
          '';
        };

        packages = {
          default = self.packages.${system}.scip-java;
          # The `scip-java` CLI, built from this checkout. The JDK it is
          # wrapped with is also the one it indexes codebases on, so it
          # matches the JDK of the Docker image rather than the devShell's.
          scip-java = pkgs.callPackage ./nix/scip-java.nix {
            jdk = pkgs.jdk25;
            gradle = pkgs.gradle_9.override ({ java = pkgs.jdk25; });
          };
        };

        devShells = {
          default = mkDevShell pkgs.jdk17;
          jdk17 = mkDevShell pkgs.jdk17;
          jdk21 = mkDevShell pkgs.jdk21;
          jdk25 = mkDevShell pkgs.jdk25;
        };
      }
    );
}
