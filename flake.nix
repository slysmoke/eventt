{
  description = "EVE Night Trade Tools — Kotlin/Compose Desktop trading app for EVE Online";

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
          pname = "eventt";
          version = "0.0.1";

          # Требует предварительного запуска: ./gradlew createDistributable
          # Используем --impure и PWD чтобы обойти ограничение git-tracked файлов
          src = builtins.path {
            name = "eventt-dist";
            path = "${builtins.getEnv "PWD"}/app/build/compose/binaries/main/app/eventt";
          };

          nativeBuildInputs = [ pkgs.makeWrapper ];

          installPhase = ''
            mkdir -p $out
            cp -r . $out/

            wrapProgram $out/bin/eventt \
              --set LD_LIBRARY_PATH ${pkgs.lib.makeLibraryPath (with pkgs; [
                libGL
                libGLU
                libxkbcommon
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

            # лаунчер ищет конфиг по имени бинарника; makeWrapper переименовывает его в .eventt-wrapped
            ln -s $out/lib/app/eventt.cfg $out/lib/app/.eventt-wrapped.cfg
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
            libXi     # X Input Extension, needed by Skia/AWT for input handling
            libX11    # needed by Skia/AWT and by the X11 global-hotkey backend (XGrabKey)
          ];

          shellHook = ''
            echo "Kotlin Desktop Development Environment"
            echo "JDK: $(java -version 2>&1 | head -n 1)"
            echo "Kotlin: $(kotlin -version 2>&1)"
            echo "Gradle: $(gradle --version 2>&1 | head -n 3)"

            export LD_LIBRARY_PATH=${pkgs.libGL}/lib:${pkgs.libGLU}/lib:${pkgs.libxkbcommon}/lib:${pkgs.libxi}/lib:${pkgs.libx11}/lib:$LD_LIBRARY_PATH
          '';
        };
      });
}
