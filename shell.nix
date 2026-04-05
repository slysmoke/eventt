{pkgs ? import <nixpkgs> {}}: let
  # Импортируем конкретную версию nixpkgs для claude-code
  oldPkgs = import (fetchTarball "https://github.com/NixOS/nixpkgs/archive/15c6719d8c604779cf59e03c245ea61d3d7ab69b.tar.gz") {
    config = pkgs.config;
  };
in
  pkgs.mkShell {
    buildInputs = with pkgs; [
      flutter
      ninja
      pkg-config
      libsecret
      gtk3
      # Ключевой момент: добавляем sysprof напрямую
      sysprof
      glib
      # Иногда требуется явно пробросить glib
      glib
      # SQLite — нужен для flutter test (drift unit tests)
      sqlite

      # И, конечно же, наш старый добрый claude-code
      oldPkgs.claude-code
    ];

    shellHook = ''
      # Принудительно обновляем пути для pkg-config
      export PKG_CONFIG_PATH="${pkgs.sysprof.dev}/lib/pkgconfig:${pkgs.glib.dev}/lib/pkgconfig:$PKG_CONFIG_PATH"

      # sqlite3 нужен для flutter test (drift unit tests используют libsqlite3.so напрямую)
      export LD_LIBRARY_PATH="${pkgs.sqlite.out}/lib:$LD_LIBRARY_PATH"

      # Для ручной сборки C++ проектов (не Flutter) — чтобы CMake не лез в /usr/local
      # Не устанавливать CMAKE_INSTALL_PREFIX глобально — это ломает flutter run

      echo "--- Environment Ready ---"
    '';
  }
