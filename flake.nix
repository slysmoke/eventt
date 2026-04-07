{
  description = "EVE Night Trade Tools - Cross-platform EVE Online trading client";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            android_sdk.accept_license = true;
            allowUnfree = true;
          };
        };

        # Version from git tag or "dev" for local builds
        version = if (self ? shortRev) then self.shortRev else "dev";

        # Common build inputs for all platforms
        buildInputs = with pkgs; [
          flutter
          ninja
          pkg-config
          libsecret
          gtk3
          sysprof
          glib
          sqlite
          keybinder3
        ];

      in
      {
        # Dev shell for development (used by all platforms)
        devShells.default = pkgs.mkShell {
          buildInputs = buildInputs;

          shellHook = ''
            export PKG_CONFIG_PATH="${pkgs.sysprof.dev}/lib/pkgconfig:${pkgs.glib.dev}/lib/pkgconfig:${pkgs.keybinder3}/lib/pkgconfig:$PKG_CONFIG_PATH"
            export LD_LIBRARY_PATH="${pkgs.sqlite.out}/lib:$LD_LIBRARY_PATH"
            echo "--- EVE NTT Environment Ready ---"
          '';
        };

        # Linux package - builds Flutter bundle
        packages.linux = pkgs.stdenv.mkDerivation {
          pname = "eve-ntt";
          inherit version;

          src = ./.;

          nativeBuildInputs = with pkgs; [
            flutter
            ninja
            pkg-config
            python3
          ];

          buildInputs = with pkgs; [
            libsecret
            gtk3
            sysprof
            glib
            sqlite
            keybinder3
          ];

          # Pass Dart defines from environment variables
          EVE_CLIENT_ID = builtins.getEnv "EVE_CLIENT_ID";
          EVE_CLIENT_SECRET = builtins.getEnv "EVE_CLIENT_SECRET";

          buildPhase = ''
            runHook preBuild

            # Get dependencies first
            flutter pub get

            # Build for Linux
            flutter build linux --release \
              --dart-define=EVE_CLIENT_ID=$EVE_CLIENT_ID \
              --dart-define=EVE_CLIENT_SECRET=$EVE_CLIENT_SECRET

            runHook postBuild
          '';

          installPhase = ''
            runHook preInstall

            # Copy the Flutter bundle to output
            cp -r build/linux/x64/release/bundle/* $out/

            runHook postInstall
          '';

          dontStrip = false;
        };

        # Default package
        packages.default = self.packages.${system}.linux;

        # App for direct execution
        apps.default = {
          type = "app";
          program = "${self.packages.${system}.linux}/AppDir/usr/bin/eve_ntt";
        };
      }
    );
}
