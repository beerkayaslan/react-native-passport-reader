const {
  withEntitlementsPlist,
  withInfoPlist,
  withAndroidManifest,
} = require("expo/config-plugins");

/**
 * Expo config plugin to configure NFC entitlements and permissions
 * for both iOS and Android platforms.
 */
function withNFCEntitlement(config) {
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

  return config;
}

module.exports = withNFCEntitlement;
