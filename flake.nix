{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs";
    flake-parts.url = "github:hercules-ci/flake-parts";
    cryonet.url = "github:kagari-org/cryonet";
  };

  outputs = inputs@{
    self, nixpkgs, flake-parts, ...
  }: flake-parts.lib.mkFlake { inherit inputs; } {
    systems = [ "x86_64-linux" ];
    perSystem = { pkgs, system, ... }: let
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        buildToolsVersions = [ "35.0.0" ];
        cmdLineToolsVersion = "latest";
        platformToolsVersion = "latest";
        toolsVersion = null;
        includeEmulator = false;
        includeSystemImages = false;
        includeCmake = false;
        includeNDK = false;
        includeSources = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
    in {
      _module.args.pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      devShells.default = pkgs.mkShell {
        nativeBuildInputs = with pkgs; [ gradle jdk21 ktlint ];
        JAVA_HOME = "${pkgs.jdk21}";
        ANDROID_HOME = "${sdk}";
        ANDROID_SDK_ROOT = "${sdk}";
        shellHook = ''
          export ANDROID_USER_HOME="$PWD/.cache/android"
          export GRADLE_USER_HOME="$PWD/.cache/gradle"
          mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"

          ln -sfn ${inputs.cryonet.packages.${system}.android} native
        '';
      };
    };
  };
}
