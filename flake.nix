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
        packages.default = pkgs.stdenv.mkDerivation {
          pname = "eve-trader";
          version = "1.0.0";

          # Требует предварительного запуска: ./gradlew createDistributable
          # Используем --impure и PWD чтобы обойти ограничение git-tracked файлов
          src = builtins.path {
            name = "eve-trader-dist";
            path = "${builtins.getEnv "PWD"}/app/build/compose/binaries/main/app/eve-trader";
          };

          nativeBuildInputs = [ pkgs.makeWrapper ];

          installPhase = ''
            mkdir -p $out
            cp -r . $out/

            wrapProgram $out/bin/eve-trader \
              --set LD_LIBRARY_PATH ${pkgs.lib.makeLibraryPath (with pkgs; [
                libGL
                libGLU
                libxkbcommon
                libXtst
                libXt
                libXi
                libX11
                fontconfig
                cups
                libxinerama
                libxrandr
                libxrender
                libxext
                libxfixes
                libxcursor
                libxcomposite
                libxdamage
                alsa-lib
                file
                gtk3
              ])}

            # лаунчер ищет конфиг по имени бинарника; makeWrapper переименовывает его в .eve-trader-wrapped
            ln -s $out/lib/app/eve-trader.cfg $out/lib/app/.eve-trader-wrapped.cfg

            # JNativeHook пытается распаковать .so в директорию приложения (read-only в Nix store)
            echo "java-options=-Djnativehook.lib.path=/tmp" >> $out/lib/app/eve-trader.cfg
          '';
        };

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
