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
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream

class PassportReaderModule : Module() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingPromise: Promise? = null
    private var pendingMrzKey: BACKeySpec? = null

    override fun definition() = ModuleDefinition {
        Name("PassportReader")

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

        passportService.sendSelectApplet(false)
        passportService.doBAC(bacKey)

        // Read DG1 (MRZ data)
        val dg1In: InputStream = passportService.getInputStream(PassportService.EF_DG1)
        val dg1 = DG1File(dg1In)
        val mrzInfo = dg1.mrzInfo

        // Read DG2 (facial image) - extract as base64
        var faceImageBase64: String? = null
        try {
            val dg2In: InputStream = passportService.getInputStream(PassportService.EF_DG2)
            val dg2 = DG2File(dg2In)
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
        var signatureImageBase64: String? = null
        try {
            val dg7In: InputStream = passportService.getInputStream(PassportService.EF_DG7)
            val dg7 = DG7File(dg7In)
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
            val dg11In: InputStream = passportService.getInputStream(PassportService.EF_DG11)
            val dg11 = DG11File(dg11In)
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
        var issuingAuthority: String? = null
        var dateOfIssue: String? = null
        var endorsements: String? = null
        var taxOrExitRequirements: String? = null
        try {
            val dg12In: InputStream = passportService.getInputStream(PassportService.EF_DG12)
            val dg12 = DG12File(dg12In)
            issuingAuthority = dg12.issuingAuthority
            dateOfIssue = dg12.dateOfIssue
            endorsements = dg12.endorsementsAndObservations
            taxOrExitRequirements = dg12.taxOrExitRequirements
        } catch (_: Exception) {}

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
            "activeAuthentication" to false,
            "passiveAuthentication" to false,
            "nfcDataGroups" to readGroups,
            "isVerified" to true,
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

    private fun clean(value: String?): String {
        if (value == null) return ""
        return value
            .replace("<", " ")
            .trim()
            .let { if (it == "?") "" else it }
    }
}
