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

        # Linux package - builds AppImage
        packages.linux = pkgs.stdenv.mkDerivation {
          pname = "eve-ntt";
          inherit version;

          src = ./.;

          nativeBuildInputs = with pkgs; [
            flutter
            ninja
            pkg-config
            appimagekit
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

            # Create AppDir structure
            APPDIR=$out/AppDir
            mkdir -p $APPDIR/usr/bin
            mkdir -p $APPDIR/usr/lib
            mkdir -p $APPDIR/usr/share/applications
            mkdir -p $APPDIR/usr/share/icons/hicolor/128x128/apps

            cp -r build/linux/x64/release/bundle/* $APPDIR/usr/bin/

            # Bundle libsqlite3
            cp ${pkgs.sqlite.out}/lib/libsqlite3.so.0 $APPDIR/usr/lib/
            ln -sf libsqlite3.so.0 $APPDIR/usr/lib/libsqlite3.so

            # Create desktop entry
            cat > $APPDIR/usr/share/applications/eve_ntt.desktop << 'EOF'
            [Desktop Entry]
            Name=EVE Night Trade Tools
            Exec=eve_ntt
            Icon=eve_ntt
            Type=Application
            Categories=Game;
            EOF

            # Generate icon
            python3 -c "
            import struct, zlib
            def png(w,h,r,g,b):
              def chunk(t,d):
                c=t+d;_crc=struct.pack('>I',zlib.crc32(c)&0xffffffff)
                return struct.pack('>I',len(d))+t+d+_crc
              sig=b'\\x89PNG\\r\\n\\x1a\\n'
              ihdr=chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))
              raw=b''.join(b'\\x00'+bytes([r,g,b])*w for _ in range(h))
              idat=chunk(b'IDAT',zlib.compress(raw))
              iend=chunk(b'IEND',b'')
              return sig+ihdr+idat+iend
            open('$APPDIR/usr/share/icons/hicolor/128x128/apps/eve_ntt.png','wb').write(png(128,128,0,180,191))
            "

            # Build AppImage
            export ARCH=x86_64
            appimagetool $APPDIR $out/eve-ntt-${version}-x86_64.AppImage

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
