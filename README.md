# rn-passport-reader

React Native Expo module for reading NFC-enabled passports and ID cards using BAC (Basic Access Control). Extracts all available data including face photo, signature, and personal details. Supports both iOS and Android.

## Features

- Read passport/ID card data via NFC (ICAO 9303 compliant)
- BAC (Basic Access Control) authentication
- **DG1** — MRZ data (name, nationality, document number, dates, gender)
- **DG2** — Face photo (returned as base64 JPEG)
- **DG7** — Signature/usual mark image (returned as base64)
- **DG11** — Additional personal details (place of birth, full name, personal number, telephone, profession, etc.)
- **DG12** — Additional document details (issuing authority, date of issue, endorsements)
- **DG14** — Security info for active authentication
- Active & Passive authentication status
- Expo Config Plugin for automatic NFC entitlement setup
- iOS: Uses [NFCPassportReader](https://github.com/AndyQ/NFCPassportReader) (Swift Package)
- Android: Uses [JMRTD](https://jmrtd.org/) + Scuba

## Installation

```bash
npx expo install rn-passport-reader
```

Or with npm/yarn:

```bash
npm install rn-passport-reader
# or
yarn add rn-passport-reader
```

## Configuration

### Expo (Managed Workflow)

Add the config plugin to your `app.json` / `app.config.js`:

```json
{
  "expo": {
    "plugins": ["rn-passport-reader"]
  }
}
```

This automatically configures:
- **iOS**: NFC Tag Reading entitlement, `NFCReaderUsageDescription`, ISO 7816 AID identifiers
- **Android**: NFC permission and hardware feature declaration

### Passive Authentication (CSCA Master List)

For full passive authentication (verifying the passport's certificate chain against your country's CSCA certificates), you need to provide a PEM file containing the CSCA master list.

1. Obtain the CSCA master list PEM file from [ICAO PKD](https://pkddownloadsg.icao.int/) or your country's authority
2. Place the file in your project (e.g., `assets/bundle.pem`)
3. Configure the plugin with the path:

```json
{
  "expo": {
    "plugins": [
      ["rn-passport-reader", { "masterListPem": "./assets/bundle.pem" }]
    ]
  }
}
```

The plugin will automatically copy the PEM file to both iOS and Android native projects during prebuild.

> **Without the PEM file:** Passive authentication on Android will only verify data group hashes against the SOD (data integrity), but won't verify the certificate chain. On iOS, `passiveAuthentication` will return `false` if no master list is available.

### iOS Additional Setup (Bare Workflow)

If you're not using Expo managed workflow, you need to manually configure:

**iOS** — Add to your `Info.plist`:
```xml
<key>NFCReaderUsageDescription</key>
<string>NFC is used for identity verification.</string>
<key>com.apple.developer.nfc.readersession.iso7816.select-identifiers</key>
<array>
    <string>A0000002471001</string>
    <string>A0000002472001</string>
    <string>00000000000000</string>
</array>
```

And enable **Near Field Communication Tag Reading** capability in your Xcode project.

**Android** — Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc" android:required="true" />
```

## Usage

```typescript
import { readPassport, isNFCSupported, PassportData } from "rn-passport-reader";

// Check NFC availability
const nfcAvailable = isNFCSupported();

// Read passport (parameters from MRZ: serial number, date of birth, expiry date in YYMMDD)
try {
  const data: PassportData = await readPassport(
    "AB1234567",  // Document number / serial number
    "900101",     // Date of birth (YYMMDD)
    "300101"      // Date of expiry (YYMMDD)
  );

  console.log(data.firstName);
  console.log(data.lastName);
  console.log(data.nationality);
  console.log(data.isVerified);

  // Display face photo
  if (data.faceImageBase64) {
    // Use in an <Image> component:
    // <Image source={{ uri: `data:image/jpeg;base64,${data.faceImageBase64}` }} />
    console.log("Face photo available");
  }

  // Display signature
  if (data.signatureImageBase64) {
    console.log("Signature image available");
  }

  // Additional personal info
  if (data.placeOfBirth) console.log("Place of birth:", data.placeOfBirth);
  if (data.issuingAuthority) console.log("Issued by:", data.issuingAuthority);
} catch (error) {
  console.error("NFC read failed:", error);
}
```

## API

### `readPassport(serialNumber, dateOfBirth, dateOfExpiry)`

Reads passport data via NFC. Extracts all available data groups from the chip.

| Parameter | Type | Description |
|-----------|------|-------------|
| `serialNumber` | `string` | Document number / passport number |
| `dateOfBirth` | `string` | Date of birth in `YYMMDD` format |
| `dateOfExpiry` | `string` | Date of expiry in `YYMMDD` format |

Returns `Promise<PassportData>`.

### `isNFCSupported()`

Returns `boolean` indicating whether NFC is available and enabled on the device.

### `PassportData`

```typescript
interface PassportData {
  // DG1 — MRZ Data
  firstName: string;
  lastName: string;
  gender: string;
  nationality: string;
  documentNumber: string;
  serialNumber: string;
  dateOfBirth: string;
  expiryDate: string;

  // Authentication
  activeAuthentication: boolean;
  passiveAuthentication: boolean;
  isVerified: boolean;
  nfcDataGroups: string[];

  // DG2 — Face Photo (base64 JPEG)
  faceImageBase64?: string;

  // DG7 — Signature Image (base64)
  signatureImageBase64?: string;

  // DG11 — Additional Personal Details
  placeOfBirth?: string;
  fullName?: string;
  otherNames?: string[];
  personalNumber?: string;
  telephone?: string;
  profession?: string;
  title?: string;
  personalSummary?: string;
  custodyInfo?: string;

  // DG12 — Additional Document Details
  issuingAuthority?: string;
  dateOfIssue?: string;
  endorsements?: string;
  taxOrExitRequirements?: string;
}
```

> **Note:** Optional fields are only present if the passport/ID card chip contains the corresponding data group. Not all documents store all data groups.

## Data Groups

| Data Group | Content | Field(s) |
|-----------|---------|----------|
| DG1 | MRZ info | `firstName`, `lastName`, `gender`, `nationality`, `documentNumber`, `serialNumber`, `dateOfBirth`, `expiryDate` |
| DG2 | Face photo | `faceImageBase64` |
| DG7 | Signature/mark | `signatureImageBase64` |
| DG11 | Personal details | `placeOfBirth`, `fullName`, `otherNames`, `personalNumber`, `telephone`, `profession`, `title`, `personalSummary`, `custodyInfo` |
| DG12 | Document details | `issuingAuthority`, `dateOfIssue`, `endorsements`, `taxOrExitRequirements` |
| DG14 | Security info | Used for active authentication |

## Requirements

- iOS 15.1+
- Android minSdk 24 (Android 7.0+)
- Expo SDK 51+
- Device with NFC hardware

## License

MIT
