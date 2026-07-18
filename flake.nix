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
          version = "1.0.11";

          # Скачивает уже собранный Linux app-image из GitHub Release (собирается и
          # публикуется на CI по тегу — .github/workflows/release.yml). version/hash
          # ниже правит тот же workflow автоматически при каждом релизном теге, так
          # что сборка полностью герметична — не нужен --impure и локальный gradlew.
          src = pkgs.fetchzip {
            url = "https://github.com/slysmoke/eventt/releases/download/v1.0.11/eventt-linux.zip";
            hash = "sha256-Gk9sbkK/LPTyZYIfNZUgLQk5tRsaPyMKnDNSqyHfHy8=";
          };

          # Бинарники в архиве собраны на обычном Ubuntu CI-раннере — их ELF-интерпретер
          # (/lib64/ld-linux...) и RPATH не подходят для NixOS. autoPatchelfHook переписывает
          # оба под buildInputs ниже.
          nativeBuildInputs = [ pkgs.makeWrapper pkgs.autoPatchelfHook ];
          buildInputs = with pkgs; [
            stdenv.cc.cc.lib
            zlib
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
            libXtst
            alsa-lib
            gtk3
          ];

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

            # jpackage app-image не создаёт .desktop/иконку в XDG-путях (это делает только
            # --type deb/rpm) — добавляем вручную, чтобы приложение появилось в меню.
            install -Dm444 $out/lib/eventt.png $out/share/icons/hicolor/128x128/apps/eventt.png
            mkdir -p $out/share/applications
            cat > $out/share/applications/eventt.desktop <<EOF
            [Desktop Entry]
            Type=Application
            Name=EVE Night Trade Tools
            Comment=EVE Online trading tools
            Exec=$out/bin/eventt
            Icon=eventt
            Categories=Game;Utility;
            Terminal=false
            EOF
          '';
        };

        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            jdk21
            gradle_9
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
