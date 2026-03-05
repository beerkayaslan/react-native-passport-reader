import ExpoModulesCore
import NFCPassportReader
import CoreNFC
import UIKit

public class PassportReaderModule: Module {
    private var passportReader = PassportReader()

    public func definition() -> ModuleDefinition {
        Name("PassportReader")

        Events("onPassportReadProgress")

        AsyncFunction("readPassport") { (serialNumber: String, dateOfBirth: String, dateOfExpiry: String, promise: Promise) in
            Task { @MainActor in
                await self.performRead(
                    serialNumber: serialNumber,
                    dateOfBirth: dateOfBirth,
                    dateOfExpiry: dateOfExpiry,
                    promise: promise
                )
            }
        }

        Function("isNFCSupported") { () -> Bool in
            return NFCTagReaderSession.readingAvailable
        }
    }

    @MainActor
    private func performRead(
        serialNumber: String,
        dateOfBirth: String,
        dateOfExpiry: String,
        promise: Promise
    ) async {
        let normalizedSerialNumber = normalizeDocumentNumber(serialNumber)
        let normalizedDateOfBirth = normalizeDate(dateOfBirth)
        let normalizedDateOfExpiry = normalizeDate(dateOfExpiry)

        guard normalizedSerialNumber.count >= 6,
              normalizedDateOfBirth.count == 6,
              normalizedDateOfExpiry.count == 6 else {
            promise.reject("ERR_MRZ", "Invalid MRZ fields. Please scan the document again.")
            return
        }

        let mrzKey = buildMRZKey(
            passportNumber: normalizedSerialNumber,
            dateOfBirth: normalizedDateOfBirth,
            dateOfExpiry: normalizedDateOfExpiry
        )

        if let masterListURL = resolveMasterListURL() {
            passportReader.setMasterListURL(masterListURL)
        }

        let customMessageHandler: (NFCViewDisplayMessage) -> String? = { [weak self] message in
            switch message {
            case .requestPresentPassport:
                self?.sendEvent("onPassportReadProgress", [
                    "progress": 0,
                    "step": 0,
                    "totalSteps": 8,
                    "message": "Hold your NFC-enabled ID near the phone."
                ])
                return "Hold your NFC-enabled ID near the phone."
            case .readingDataGroupProgress(let id, let progress):
                let progressInt = Int(progress * 100)
                self?.sendEvent("onPassportReadProgress", [
                    "progress": progressInt,
                    "step": Int(id.rawValue),
                    "totalSteps": 8,
                    "message": "Reading data group \(id)... \(progressInt)%"
                ])
                return "Reading data group \(id)... \(progressInt)%"
            case .activeAuthentication:
                self?.sendEvent("onPassportReadProgress", [
                    "progress": 90,
                    "step": 7,
                    "totalSteps": 8,
                    "message": "Authenticating identity..."
                ])
                return "Authenticating identity..."
            case .authenticatingWithPassport:
                self?.sendEvent("onPassportReadProgress", [
                    "progress": 80,
                    "step": 6,
                    "totalSteps": 8,
                    "message": "Performing authentication..."
                ])
                return "Performing authentication..."
            case .successfulRead:
                self?.sendEvent("onPassportReadProgress", [
                    "progress": 100,
                    "step": 8,
                    "totalSteps": 8,
                    "message": "Read successful"
                ])
                return "Read successful"
            default:
                return nil
            }
        }

        do {
            let dataGroups: [DataGroupId] = [.COM, .SOD, .DG1, .DG2, .DG7, .DG11, .DG12, .DG14]
            let passport = try await passportReader.readPassport(
                mrzKey: mrzKey,
                tags: dataGroups,
                useExtendedMode: true,
                customDisplayMessage: customMessageHandler
            )

            let result = buildResult(passport: passport, serialNumber: normalizedSerialNumber)
            promise.resolve(result)
        } catch {
            promise.reject("ERR_NFC", error.localizedDescription)
        }
    }

    private func buildResult(passport: NFCPassportModel, serialNumber: String) -> [String: Any] {
        let firstName = clean(passport.firstName)
        let lastName = clean(passport.lastName)
        let gender = clean(passport.gender)
        let nationality = clean(passport.nationality)
        let documentNumber = clean(passport.documentNumber)
        let dateOfBirth = clean(passport.dateOfBirth)
        let expiryDate = clean(passport.documentExpiryDate)
        let activeAuth = passport.activeAuthenticationPassed
        let passiveAuth = passport.passportCorrectlySigned

        let readGroups = passport.dataGroupsRead.keys
            .map { String($0.rawValue) }
            .sorted()

        // DG2 - Face photo as base64 JPEG
        var faceImageBase64: String? = nil
        if let faceImage = passport.passportImage {
            if let jpegData = faceImage.jpegData(compressionQuality: 0.9) {
                faceImageBase64 = jpegData.base64EncodedString()
            }
        }

        // DG7 - Signature image as base64 JPEG
        var signatureImageBase64: String? = nil
        if let dg7 = passport.dataGroupsRead[.DG7] as? DataGroup7 {
            let imageRawData = Data(dg7.imageData)
            if imageRawData.count > 0 {
                // Try to create UIImage from the raw data
                if let sigImage = UIImage(data: imageRawData) {
                    if let jpegData = sigImage.jpegData(compressionQuality: 0.9) {
                        signatureImageBase64 = jpegData.base64EncodedString()
                    }
                } else {
                    // Fallback: return raw data as base64
                    signatureImageBase64 = imageRawData.base64EncodedString()
                }
            }
        }

        // DG11 - Additional personal details
        var placeOfBirth: String? = nil
        var fullName: String? = nil
        var personalNumber: String? = nil
        var telephone: String? = nil
        var profession: String? = nil
        var title: String? = nil
        var personalSummary: String? = nil
        var custodyInfo: String? = nil

        if let dg11 = passport.dataGroupsRead[.DG11] as? DataGroup11 {
            fullName = dg11.fullName
            personalNumber = dg11.personalNumber
            placeOfBirth = dg11.placeOfBirth
            telephone = dg11.telephone
            profession = dg11.profession
            title = dg11.title
            personalSummary = dg11.personalSummary
            custodyInfo = dg11.custodyInfo
        }

        // DG12 - Additional document details
        var issuingAuthority: String? = nil
        var dateOfIssue: String? = nil
        var endorsements: String? = nil
        var taxOrExitRequirements: String? = nil

        if let dg12 = passport.dataGroupsRead[.DG12] as? DataGroup12 {
            issuingAuthority = dg12.issuingAuthority
            dateOfIssue = dg12.dateOfIssue
            endorsements = dg12.endorsementsOrObservations
            taxOrExitRequirements = dg12.taxOrExitRequirements
        }

        var result: [String: Any] = [
            "firstName": firstName,
            "lastName": lastName,
            "gender": gender,
            "nationality": nationality,
            "documentNumber": documentNumber,
            "serialNumber": serialNumber,
            "dateOfBirth": dateOfBirth,
            "expiryDate": expiryDate,
            "activeAuthentication": activeAuth,
            "passiveAuthentication": passiveAuth,
            "nfcDataGroups": readGroups,
            "isVerified": activeAuth || passiveAuth,
        ]

        // Optional fields
        if let v = faceImageBase64 { result["faceImageBase64"] = v }
        if let v = signatureImageBase64 { result["signatureImageBase64"] = v }
        if let v = placeOfBirth { result["placeOfBirth"] = v }
        if let v = fullName { result["fullName"] = v }
        if let v = personalNumber, !v.isEmpty { result["personalNumber"] = v }
        if let v = telephone, !v.isEmpty { result["telephone"] = v }
        if let v = profession, !v.isEmpty { result["profession"] = v }
        if let v = title, !v.isEmpty { result["title"] = v }
        if let v = personalSummary, !v.isEmpty { result["personalSummary"] = v }
        if let v = custodyInfo, !v.isEmpty { result["custodyInfo"] = v }
        if let v = issuingAuthority, !v.isEmpty { result["issuingAuthority"] = v }
        if let v = dateOfIssue, !v.isEmpty { result["dateOfIssue"] = v }
        if let v = endorsements, !v.isEmpty { result["endorsements"] = v }
        if let v = taxOrExitRequirements, !v.isEmpty { result["taxOrExitRequirements"] = v }

        return result
    }

    private func clean(_ value: String) -> String {
        let normalized = value
            .replacingOccurrences(of: "<", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized == "?" ? "" : normalized
    }

    private func buildMRZKey(passportNumber: String, dateOfBirth: String, dateOfExpiry: String) -> String {
        func pad(_ value: String, length: Int) -> String {
            var s = value
            while s.count < length { s += "<" }
            return String(s.prefix(length))
        }

        func checksum(_ str: String) -> Int {
            let weights = [7, 3, 1]
            let chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<"
            var sum = 0
            for (i, ch) in str.enumerated() {
                let value: Int
                if let idx = chars.firstIndex(of: ch) {
                    value = chars.distance(from: chars.startIndex, to: idx)
                } else {
                    value = 0
                }
                sum += value * weights[i % 3]
            }
            return sum % 10
        }

        let pptNr = pad(passportNumber, length: 9)
        let dob = pad(dateOfBirth, length: 6)
        let exp = pad(dateOfExpiry, length: 6)

        let pptNrChk = checksum(pptNr)
        let dobChk = checksum(dob)
        let expChk = checksum(exp)

        return "\(pptNr)\(pptNrChk)\(dob)\(dobChk)\(exp)\(expChk)"
    }

    private func resolveMasterListURL() -> URL? {
        if let url = Bundle.main.url(forResource: "bundle", withExtension: "pem") {
            return url
        }

        let moduleBundle = Bundle(for: PassportReaderModule.self)
        if let url = moduleBundle.url(forResource: "bundle", withExtension: "pem") {
            return url
        }

        for bundle in Bundle.allBundles + Bundle.allFrameworks {
            if let url = bundle.url(forResource: "bundle", withExtension: "pem") {
                return url
            }
        }

        return nil
    }

    private func normalizeDocumentNumber(_ value: String) -> String {
        return value
            .uppercased()
            .filter { $0.isLetter || $0.isNumber || $0 == "<" }
            .replacingOccurrences(of: "<", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func normalizeDate(_ value: String) -> String {
        var normalized = value
            .uppercased()
            .filter { $0.isLetter || $0.isNumber || $0 == "<" }

        let substitutions: [Character: Character] = [
            "O": "0",
            "I": "1",
            "B": "8",
            "S": "5",
            "Z": "2"
        ]

        normalized = String(normalized.map { substitutions[$0] ?? $0 })
        if normalized.count >= 6 {
            return String(normalized.prefix(6))
        }

        return normalized + String(repeating: "<", count: 6 - normalized.count)
    }
}
