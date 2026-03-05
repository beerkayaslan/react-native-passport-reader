import { requireNativeModule } from "expo-modules-core";

export interface PassportData {
  firstName: string;
  lastName: string;
  gender: string;
  nationality: string;
  documentNumber: string;
  serialNumber: string;
  dateOfBirth: string;
  expiryDate: string;
  activeAuthentication: boolean;
  passiveAuthentication: boolean;
  nfcDataGroups: string[];
  isVerified: boolean;
  /** Base64-encoded JPEG face photo from DG2 */
  faceImageBase64?: string;
  /** Base64-encoded signature image from DG7 */
  signatureImageBase64?: string;
  /** Place of birth from DG11 */
  placeOfBirth?: string;
  /** Full name from DG11 */
  fullName?: string;
  /** Other names from DG11 */
  otherNames?: string[];
  /** Personal number from DG11 */
  personalNumber?: string;
  /** Telephone from DG11 */
  telephone?: string;
  /** Profession from DG11 */
  profession?: string;
  /** Title from DG11 */
  title?: string;
  /** Personal summary from DG11 */
  personalSummary?: string;
  /** Custody information from DG11 */
  custodyInfo?: string;
  /** Issuing authority from DG12 */
  issuingAuthority?: string;
  /** Date of issue from DG12 */
  dateOfIssue?: string;
  /** Endorsements/observations from DG12 */
  endorsements?: string;
  /** Tax or exit requirements from DG12 */
  taxOrExitRequirements?: string;
}

interface PassportReaderNative {
  readPassport(
    serialNumber: string,
    dateOfBirth: string,
    dateOfExpiry: string,
  ): Promise<PassportData>;
  isNFCSupported(): boolean;
}

const PassportReaderModule =
  requireNativeModule<PassportReaderNative>("PassportReader");

/**
 * Read passport data via NFC.
 * @param serialNumber - Document serial / passport number
 * @param dateOfBirth - Date of birth in YYMMDD format
 * @param dateOfExpiry - Date of expiry in YYMMDD format
 * @returns Passport identity data
 */
export async function readPassport(
  serialNumber: string,
  dateOfBirth: string,
  dateOfExpiry: string,
): Promise<PassportData> {
  return PassportReaderModule.readPassport(
    serialNumber,
    dateOfBirth,
    dateOfExpiry,
  );
}

/**
 * Check if NFC is available on this device.
 */
export function isNFCSupported(): boolean {
  return PassportReaderModule.isNFCSupported();
}
