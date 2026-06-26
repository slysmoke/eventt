{
  description = "EVE Trader — Kotlin/Compose Desktop trading app for EVE Online";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            jdk21
            gradle
            kotlin
            git
            libGL
            libGLU
            libxkbcommon
            fontconfig
            libXtst   # required by JNativeHook for global keyboard hooks
            libXt     # X Toolkit, transitive dep of libXtst
            libXi     # X Input Extension, also needed by JNativeHook
            libX11
          ];

          shellHook = ''
            echo "Kotlin Desktop Development Environment"
            echo "JDK: $(java -version 2>&1 | head -n 1)"
            echo "Kotlin: $(kotlin -version 2>&1)"
            echo "Gradle: $(gradle --version 2>&1 | head -n 3)"

            export LD_LIBRARY_PATH=${pkgs.libGL}/lib:${pkgs.libGLU}/lib:${pkgs.libxkbcommon}/lib:${pkgs.libxtst}/lib:${pkgs.libxt}/lib:${pkgs.libxi}/lib:${pkgs.libx11}/lib:$LD_LIBRARY_PATH
          '';
        };
      });
}
