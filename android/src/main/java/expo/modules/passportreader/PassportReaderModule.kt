package expo.modules.passportreader

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.util.Base64
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import kotlinx.coroutines.*
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.icao.*
import org.jmrtd.lds.*
import org.jmrtd.lds.iso19794.FaceImageInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.CertPathValidator
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG15File

class PassportReaderModule : Module() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingPromise: Promise? = null
    private var pendingMrzKey: BACKeySpec? = null

    override fun definition() = ModuleDefinition {
        Name("PassportReader")

        Events("onPassportReadProgress")

        AsyncFunction("readPassport") { serialNumber: String, dateOfBirth: String, dateOfExpiry: String, promise: Promise ->
            val activity = appContext.activityProvider?.currentActivity
            if (activity == null) {
                promise.reject("ERR_NO_ACTIVITY", "Activity not found.", null)
                return@AsyncFunction
            }

            nfcAdapter = NfcAdapter.getDefaultAdapter(activity)
            if (nfcAdapter == null || !nfcAdapter!!.isEnabled) {
                promise.reject("ERR_NFC", "NFC is not available. Please enable NFC.", null)
                return@AsyncFunction
            }

            pendingPromise = promise
            pendingMrzKey = BACKey(serialNumber, dateOfBirth, dateOfExpiry)

            enableNfcForegroundDispatch(activity)
        }

        Function("isNFCSupported") {
            val activity = appContext.activityProvider?.currentActivity ?: return@Function false
            val adapter = NfcAdapter.getDefaultAdapter(activity)
            return@Function adapter != null && adapter.isEnabled
        }

        OnNewIntent { intent ->
            if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
                val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
                }
                tag?.let { handleNfcTag(it) }
            }
        }

        OnDestroy {
            val activity = appContext.activityProvider?.currentActivity
            activity?.let { disableNfcForegroundDispatch(it) }
        }
    }

    private fun enableNfcForegroundDispatch(activity: Activity) {
        val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getActivity(activity, 0, intent, flags)
        val techList = arrayOf(arrayOf(IsoDep::class.java.name))
        nfcAdapter?.enableForegroundDispatch(activity, pendingIntent, null, techList)
    }

    private fun disableNfcForegroundDispatch(activity: Activity) {
        try {
            nfcAdapter?.disableForegroundDispatch(activity)
        } catch (_: Exception) {}
    }

    private fun handleNfcTag(tag: Tag) {
        val promise = pendingPromise ?: return
        val bacKey = pendingMrzKey ?: return
        pendingPromise = null
        pendingMrzKey = null

        val activity = appContext.activityProvider?.currentActivity
        activity?.let { disableNfcForegroundDispatch(it) }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = readPassportFromTag(tag, bacKey)
                withContext(Dispatchers.Main) {
                    promise.resolve(result)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("ERR_NFC_READ", e.message ?: "Unknown error", e)
                }
            }
        }
    }

    private fun readPassportFromTag(tag: Tag, bacKey: BACKeySpec): Map<String, Any?> {
        val isoDep = IsoDep.get(tag)
        isoDep.timeout = 10000

        val cardService = CardService.getInstance(isoDep)
        cardService.open()

        val passportService = PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false,
            true
        )
        passportService.open()

        // Ensure full BouncyCastle provider is available for crypto operations
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        passportService.sendSelectApplet(false)
        passportService.doBAC(bacKey)

        // Total steps for progress: DG1, SOD, DG2, DG7, DG11, DG12, PassiveAuth, ActiveAuth = 8
        val totalSteps = 8
        var currentStep = 0

        fun emitProgress(step: Int, message: String) {
            currentStep = step
            val progress = (step.toDouble() / totalSteps * 100).toInt()
            sendEvent("onPassportReadProgress", mapOf(
                "progress" to progress,
                "step" to step,
                "totalSteps" to totalSteps,
                "message" to message
            ))
        }

        // Collect raw bytes for passive authentication hash verification
        val dgRawBytes = mutableMapOf<Int, ByteArray>()

        // Read DG1 (MRZ data)
        emitProgress(1, "Reading MRZ data...")
        val dg1Bytes = readAllBytes(passportService.getInputStream(PassportService.EF_DG1))
        dgRawBytes[1] = dg1Bytes
        val dg1 = DG1File(ByteArrayInputStream(dg1Bytes))
        val mrzInfo = dg1.mrzInfo

        // Read SOD (Security Object Document) for passive authentication
        emitProgress(2, "Reading security data...")
        var sodFile: SODFile? = null
        try {
            val sodIn = passportService.getInputStream(PassportService.EF_SOD)
            sodFile = SODFile(sodIn)
        } catch (_: Exception) {}

        // Read DG2 (facial image) - extract as base64
        emitProgress(3, "Reading face photo...")
        var faceImageBase64: String? = null
        try {
            val dg2Bytes = readAllBytes(passportService.getInputStream(PassportService.EF_DG2))
            dgRawBytes[2] = dg2Bytes
            val dg2 = DG2File(ByteArrayInputStream(dg2Bytes))
            if (dg2.faceInfos.isNotEmpty()) {
                val faceInfo = dg2.faceInfos[0]
                if (faceInfo.faceImageInfos.isNotEmpty()) {
                    val faceImageInfo = faceInfo.faceImageInfos[0]
                    val imageLength = faceImageInfo.imageLength
                    val dataIn = DataInputStream(faceImageInfo.imageInputStream)
                    val buffer = ByteArray(imageLength)
                    dataIn.readFully(buffer)
                    faceImageBase64 = Base64.encodeToString(buffer, Base64.NO_WRAP)
                }
            }
        } catch (_: Exception) {}

        // Read DG7 (signature image)
        emitProgress(4, "Reading signature image...")
        var signatureImageBase64: String? = null
        try {
            val dg7Bytes = readAllBytes(passportService.getInputStream(PassportService.EF_DG7))
            dgRawBytes[7] = dg7Bytes
            val dg7 = DG7File(ByteArrayInputStream(dg7Bytes))
            if (dg7.images.isNotEmpty()) {
                val sigInfo = dg7.images[0]
                val imageLength = sigInfo.imageLength
                val dataIn = DataInputStream(sigInfo.imageInputStream)
                val buffer = ByteArray(imageLength)
                dataIn.readFully(buffer)
                signatureImageBase64 = Base64.encodeToString(buffer, Base64.NO_WRAP)
            }
        } catch (_: Exception) {}

        // Read DG11 (additional personal info)
        emitProgress(5, "Reading personal details...")
        var placeOfBirth: String? = null
        var fullName: String? = null
        var otherNames: List<String>? = null
        var personalNumber: String? = null
        var telephone: String? = null
        var profession: String? = null
        var title: String? = null
        var personalSummary: String? = null
        var custodyInfo: String? = null
        try {
            val dg11Bytes = readAllBytes(passportService.getInputStream(PassportService.EF_DG11))
            dgRawBytes[11] = dg11Bytes
            val dg11 = DG11File(ByteArrayInputStream(dg11Bytes))
            placeOfBirth = dg11.placeOfBirth?.joinToString(", ")
            fullName = dg11.nameOfHolder
            otherNames = dg11.otherNames
            personalNumber = dg11.personalNumber
            telephone = dg11.telephone
            profession = dg11.profession
            title = dg11.title
            personalSummary = dg11.personalSummary
            custodyInfo = dg11.custodyInformation
        } catch (_: Exception) {}

        // Read DG12 (additional document details)
        emitProgress(6, "Reading document details...")
        var issuingAuthority: String? = null
        var dateOfIssue: String? = null
        var endorsements: String? = null
        var taxOrExitRequirements: String? = null
        try {
            val dg12Bytes = readAllBytes(passportService.getInputStream(PassportService.EF_DG12))
            dgRawBytes[12] = dg12Bytes
            val dg12 = DG12File(ByteArrayInputStream(dg12Bytes))
            issuingAuthority = dg12.issuingAuthority
            dateOfIssue = dg12.dateOfIssue
            endorsements = dg12.endorsementsAndObservations
            taxOrExitRequirements = dg12.taxOrExitRequirements
        } catch (_: Exception) {}

        // Passive Authentication: verify data group hashes against SOD
        emitProgress(7, "Verifying passport authenticity...")
        val passiveAuthPassed = performPassiveAuth(sodFile, dgRawBytes)

        // Active Authentication: challenge-response with DG15 public key
        emitProgress(8, "Performing active authentication...")
        val activeAuthPassed = performActiveAuth(passportService)

        val readGroups = mutableListOf("1")
        if (faceImageBase64 != null) readGroups.add("2")
        if (signatureImageBase64 != null) readGroups.add("7")
        if (placeOfBirth != null || fullName != null) readGroups.add("11")
        if (issuingAuthority != null || dateOfIssue != null) readGroups.add("12")

        val result = mutableMapOf<String, Any?>(
            "firstName" to clean(mrzInfo.secondaryIdentifier),
            "lastName" to clean(mrzInfo.primaryIdentifier),
            "gender" to clean(mrzInfo.gender.toString()),
            "nationality" to clean(mrzInfo.nationality),
            "documentNumber" to clean(mrzInfo.documentNumber),
            "serialNumber" to clean(mrzInfo.documentNumber),
            "dateOfBirth" to clean(mrzInfo.dateOfBirth),
            "expiryDate" to clean(mrzInfo.dateOfExpiry),
            "activeAuthentication" to activeAuthPassed,
            "passiveAuthentication" to passiveAuthPassed,
            "nfcDataGroups" to readGroups,
            "isVerified" to (activeAuthPassed || passiveAuthPassed),
        )

        // Optional fields
        faceImageBase64?.let { result["faceImageBase64"] = it }
        signatureImageBase64?.let { result["signatureImageBase64"] = it }
        placeOfBirth?.let { result["placeOfBirth"] = it }
        fullName?.let { result["fullName"] = it }
        otherNames?.takeIf { it.isNotEmpty() }?.let { result["otherNames"] = it }
        personalNumber?.takeIf { it.isNotEmpty() }?.let { result["personalNumber"] = it }
        telephone?.takeIf { it.isNotEmpty() }?.let { result["telephone"] = it }
        profession?.takeIf { it.isNotEmpty() }?.let { result["profession"] = it }
        title?.takeIf { it.isNotEmpty() }?.let { result["title"] = it }
        personalSummary?.takeIf { it.isNotEmpty() }?.let { result["personalSummary"] = it }
        custodyInfo?.takeIf { it.isNotEmpty() }?.let { result["custodyInfo"] = it }
        issuingAuthority?.takeIf { it.isNotEmpty() }?.let { result["issuingAuthority"] = it }
        dateOfIssue?.takeIf { it.isNotEmpty() }?.let { result["dateOfIssue"] = it }
        endorsements?.takeIf { it.isNotEmpty() }?.let { result["endorsements"] = it }
        taxOrExitRequirements?.takeIf { it.isNotEmpty() }?.let { result["taxOrExitRequirements"] = it }

        return result
    }

    private fun performPassiveAuth(sodFile: SODFile?, dgRawBytes: Map<Int, ByteArray>): Boolean {
        if (sodFile == null || dgRawBytes.isEmpty()) return false
        return try {
            // Step 1: Verify DG hashes match SOD stored hashes
            val storedHashes = sodFile.dataGroupHashes
            val digestAlg = sodFile.digestAlgorithm
            val md = MessageDigest.getInstance(digestAlg)

            for ((dgNum, dgBytes) in dgRawBytes) {
                val computed = md.digest(dgBytes)
                md.reset()
                val stored = storedHashes[dgNum] ?: continue
                if (!MessageDigest.isEqual(computed, stored)) {
                    return false
                }
            }

            // Step 2: Verify SOD certificate chain against CSCA master list
            val cscaCerts = loadCSCACertificates()
            if (cscaCerts.isNotEmpty()) {
                val docSigningCert = sodFile.docSigningCertificate ?: return false

                // Build trust anchors from CSCA certificates
                val trustAnchors = cscaCerts.map { TrustAnchor(it, null) }.toSet()
                val params = PKIXParameters(trustAnchors)
                params.isRevocationEnabled = false

                // Build cert path
                val certFactory = CertificateFactory.getInstance("X.509")
                val certPath = certFactory.generateCertPath(listOf(docSigningCert))

                // Validate certificate chain
                val validator = CertPathValidator.getInstance("PKIX")
                validator.validate(certPath, params)
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadCSCACertificates(): List<X509Certificate> {
        val certs = mutableListOf<X509Certificate>()
        try {
            val activity = appContext.activityProvider?.currentActivity ?: return certs
            val assetManager = activity.assets
            val inputStream = assetManager.open("bundle.pem")
            val certFactory = CertificateFactory.getInstance("X.509")

            val pemContent = inputStream.bufferedReader().readText()
            inputStream.close()

            // Parse all certificates from the PEM file
            val certRegex = Regex(
                "-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----",
                RegexOption.DOT_MATCHES_ALL
            )

            for (match in certRegex.findAll(pemContent)) {
                try {
                    val certBase64 = match.groupValues[1]
                        .replace("\\s".toRegex(), "")
                    val certBytes = Base64.decode(certBase64, Base64.DEFAULT)
                    val cert = certFactory.generateCertificate(
                        ByteArrayInputStream(certBytes)
                    ) as X509Certificate
                    certs.add(cert)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return certs
    }

    private fun performActiveAuth(passportService: PassportService): Boolean {
        return try {
            val dg15In = passportService.getInputStream(PassportService.EF_DG15)
            val dg15 = DG15File(dg15In)
            val publicKey = dg15.publicKey

            val challenge = ByteArray(8)
            SecureRandom().nextBytes(challenge)

            val (digestAlg, sigAlg) = when (publicKey) {
                is ECPublicKey -> "SHA-256" to "SHA256withECDSA"
                else -> "SHA-256" to "SHA256withRSA/ISO9796-2"
            }

            passportService.doAA(publicKey, digestAlg, sigAlg, challenge)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readAllBytes(inputStream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(4096)
        var bytesRead: Int
        while (inputStream.read(data, 0, data.size).also { bytesRead = it } != -1) {
            buffer.write(data, 0, bytesRead)
        }
        return buffer.toByteArray()
    }

    private fun clean(value: String?): String {
        if (value == null) return ""
        return value
            .replace("<", " ")
            .trim()
            .let { if (it == "?") "" else it }
    }
}
