const {
  withEntitlementsPlist,
  withInfoPlist,
  withAndroidManifest,
  withXcodeProject,
  withDangerousMod,
} = require("expo/config-plugins");
const fs = require("fs");
const path = require("path");

/**
 * Expo config plugin to configure NFC entitlements and permissions
 * for both iOS and Android platforms.
 *
 * Options:
 * - masterListPem: Path to the CSCA master list PEM file (relative to project root)
 *                  Required for passive authentication of passport NFC chips.
 */
function withNFCEntitlement(config, options = {}) {
  const masterListPem = options.masterListPem || null;

  // iOS: Add NFC Tag reading entitlement
  config = withEntitlementsPlist(config, (config) => {
    config.modResults["com.apple.developer.nfc.readersession.formats"] = [
      "TAG",
    ];
    return config;
  });

  // iOS: Add NFC reader usage description + ISO 7816 AIDs
  config = withInfoPlist(config, (config) => {
    if (!config.modResults["NFCReaderUsageDescription"]) {
      config.modResults["NFCReaderUsageDescription"] =
        "NFC is used for identity verification.";
    }
    config.modResults[
      "com.apple.developer.nfc.readersession.iso7816.select-identifiers"
    ] = ["A0000002471001", "A0000002472001", "00000000000000"];
    return config;
  });

  // Android: Add NFC permission and feature
  config = withAndroidManifest(config, (config) => {
    const manifest = config.modResults.manifest;

    if (!manifest["uses-permission"]) {
      manifest["uses-permission"] = [];
    }
    const hasNFC = manifest["uses-permission"].some(
      (p) => p.$?.["android:name"] === "android.permission.NFC"
    );
    if (!hasNFC) {
      manifest["uses-permission"].push({
        $: { "android:name": "android.permission.NFC" },
      });
    }

    if (!manifest["uses-feature"]) {
      manifest["uses-feature"] = [];
    }
    const hasNFCFeature = manifest["uses-feature"].some(
      (f) => f.$?.["android:name"] === "android.hardware.nfc"
    );
    if (!hasNFCFeature) {
      manifest["uses-feature"].push({
        $: {
          "android:name": "android.hardware.nfc",
          "android:required": "true",
        },
      });
    }

    return config;
  });

  // Copy master list PEM file if provided
  if (masterListPem) {
    // iOS: Copy PEM to Xcode project and add to bundle resources
    config = withXcodeProject(config, (config) => {
      const projectRoot = config.modRequest.projectRoot;
      const projectName = config.modRequest.projectName;
      const pemSource = path.resolve(projectRoot, masterListPem);

      if (!fs.existsSync(pemSource)) {
        throw new Error(
          `[rn-passport-reader] Master list PEM file not found: ${pemSource}`
        );
      }

      const iosProjectDir = path.join(projectRoot, "ios", projectName);

      if (!fs.existsSync(iosProjectDir)) {
        fs.mkdirSync(iosProjectDir, { recursive: true });
      }

      const pemDest = path.join(iosProjectDir, "bundle.pem");
      fs.copyFileSync(pemSource, pemDest);

      const xcodeProject = config.modResults;

      // Add resource file using project-relative path (avoids group path resolution issues)
      const resourcePath = `${projectName}/bundle.pem`;
      if (!xcodeProject.hasFile(resourcePath)) {
        xcodeProject.addResourceFile(resourcePath, {
          lastKnownFileType: "text",
        });
      }

      return config;
    });

    // Android: Copy PEM to assets directory
    config = withDangerousMod(config, [
      "android",
      (config) => {
        const projectRoot = config.modRequest.projectRoot;
        const pemSource = path.resolve(projectRoot, masterListPem);

        if (!fs.existsSync(pemSource)) {
          throw new Error(
            `[rn-passport-reader] Master list PEM file not found: ${pemSource}`
          );
        }

        const assetsDir = path.join(
          projectRoot,
          "android",
          "app",
          "src",
          "main",
          "assets"
        );

        if (!fs.existsSync(assetsDir)) {
          fs.mkdirSync(assetsDir, { recursive: true });
        }

        const pemDest = path.join(assetsDir, "bundle.pem");
        fs.copyFileSync(pemSource, pemDest);

        return config;
      },
    ]);
  }

  return config;
}

module.exports = withNFCEntitlement;
