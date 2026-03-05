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

      fs.copyFileSync(pemSource, path.join(iosProjectDir, "bundle.pem"));

      // Manually add to Xcode project to avoid xcode library's
      // addResourceFile crash when no "Resources" group exists
      const xcodeProject = config.modResults;
      const objects = xcodeProject.hash.project.objects;
      const fileName = "bundle.pem";
      const filePath = `${projectName}/bundle.pem`;

      // Check if already added
      const fileRefs = objects["PBXFileReference"] || {};
      const alreadyAdded = Object.keys(fileRefs).some(
        (key) =>
          !key.endsWith("_comment") &&
          (fileRefs[key].path === filePath ||
            fileRefs[key].path === `"${filePath}"` ||
            fileRefs[key].name === fileName)
      );
      if (alreadyAdded) return config;

      // 1. Create PBXFileReference with SOURCE_ROOT relative path
      //    SOURCE_ROOT = ios/ directory (where .xcodeproj lives)
      const fileRefUuid = xcodeProject.generateUuid();
      objects["PBXFileReference"][fileRefUuid] = {
        isa: "PBXFileReference",
        lastKnownFileType: "text",
        name: `"${fileName}"`,
        path: `"${filePath}"`,
        sourceTree: "SOURCE_ROOT",
      };
      objects["PBXFileReference"][`${fileRefUuid}_comment`] = fileName;

      // 2. Add to the main PBXGroup (project root level)
      const mainGroupKey =
        xcodeProject.getFirstProject().firstProject.mainGroup;
      const mainGroup = xcodeProject.getPBXGroupByKey(mainGroupKey);
      if (mainGroup && mainGroup.children) {
        mainGroup.children.push({ value: fileRefUuid, comment: fileName });
      }

      // 3. Create PBXBuildFile
      const buildFileUuid = xcodeProject.generateUuid();
      objects["PBXBuildFile"][buildFileUuid] = {
        isa: "PBXBuildFile",
        fileRef: fileRefUuid,
        fileRef_comment: fileName,
      };
      objects["PBXBuildFile"][`${buildFileUuid}_comment`] =
        `${fileName} in Resources`;

      // 4. Add to Copy Bundle Resources build phase
      const target = xcodeProject.getFirstTarget();
      const nativeTarget = objects["PBXNativeTarget"][target.uuid];
      if (nativeTarget && nativeTarget.buildPhases) {
        for (const phaseRef of nativeTarget.buildPhases) {
          const phase =
            objects["PBXResourcesBuildPhase"] &&
            objects["PBXResourcesBuildPhase"][phaseRef.value];
          if (phase && phase.files) {
            phase.files.push({
              value: buildFileUuid,
              comment: `${fileName} in Resources`,
            });
            break;
          }
        }
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
