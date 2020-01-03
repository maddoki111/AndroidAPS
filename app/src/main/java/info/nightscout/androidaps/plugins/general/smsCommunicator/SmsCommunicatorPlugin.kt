package info.nightscout.androidaps.plugins.general.smsCommunicator

import android.content.Intent
import android.preference.EditTextPreference
import android.preference.Preference
import android.preference.Preference.OnPreferenceChangeListener
import android.preference.PreferenceFragment
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.text.TextUtils
import com.andreabaccega.widget.ValidatingEditTextPreference
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.db.DatabaseHelper
import info.nightscout.androidaps.db.Source
import info.nightscout.androidaps.db.TempTarget
import info.nightscout.androidaps.events.EventPreferenceChange
import info.nightscout.androidaps.events.EventRefreshOverview
import info.nightscout.androidaps.interfaces.Constraint
import info.nightscout.androidaps.interfaces.PluginBase
import info.nightscout.androidaps.interfaces.PluginDescription
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.aps.loop.LoopPlugin
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunction
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload
import info.nightscout.androidaps.plugins.general.nsclient.events.EventNSClientRestart
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification
import info.nightscout.androidaps.plugins.general.smsCommunicator.events.EventSmsCommunicatorUpdateGui
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin
import info.nightscout.androidaps.queue.Callback
import info.nightscout.androidaps.utils.*
import info.nightscout.androidaps.utils.extensions.plusAssign
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import org.apache.commons.lang3.StringUtils
import java.text.Normalizer
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsCommunicatorPlugin @Inject constructor(
    private val sp: SP,
    private val resourceHelper: ResourceHelper,
    private val constraintChecker: ConstraintChecker,
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBusWrapper,
    private val profileFunction: ProfileFunction,
    private val configBuilderPlugin: ConfigBuilderPlugin,
    private val treatmentsPlugin: TreatmentsPlugin,
    private val loopPlugin: LoopPlugin,
    private val iobCobCalculatorPlugin: IobCobCalculatorPlugin
) : PluginBase(PluginDescription()
    .mainType(PluginType.GENERAL)
    .fragmentClass(SmsCommunicatorFragment::class.java.name)
    .pluginName(R.string.smscommunicator)
    .shortName(R.string.smscommunicator_shortname)
    .preferencesId(R.xml.pref_smscommunicator)
    .description(R.string.description_sms_communicator)
) {

    private val disposable = CompositeDisposable()
    var allowedNumbers: MutableList<String> = ArrayList()
    var messageToConfirm: AuthRequest? = null
    var lastRemoteBolusTime: Long = 0
    var messages = ArrayList<Sms>()

    val commands = mapOf(
        "BG" to "BG",
        "LOOP" to "LOOP STOP/DISABLE/START/ENABLE/RESUME/STATUS\nLOOP SUSPEND 20",
        "TREATMENTS" to "TREATMENTS REFRESH",
        "NSCLIENT" to "NSCLIENT RESTART",
        "PUMP" to "PUMP",
        "BASAL" to "BASAL STOP/CANCEL\nBASAL 0.3\nBASAL 0.3 20\nBASAL 30%\nBASAL 30% 20\n",
        "BOLUS" to "BOLUS 1.2\nBOLUS 1.2 MEAL",
        "EXTENDED" to "EXTENDED STOP/CANCEL\nEXTENDED 2 120",
        "CAL" to "CAL 5.6",
        "PROFILE" to "PROFILE STATUS/LIST\nPROFILE 1\nPROFILE 2 30",
        "TARGET" to "TARGET MEAL/ACTIVITY/HYPO/STOP",
        "SMS" to "SMS DISABLE/STOP",
        "CARBS" to "CARBS 12\nCARBS 12 23:05\nCARBS 12 11:05PM",
        "HELP" to "HELP\nHELP command"
    )

    override fun onStart() {
        processSettings(null)
        super.onStart()
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ event: EventPreferenceChange? -> processSettings(event) }) { FabricPrivacy.logException(it) }
    }

    override fun onStop() {
        disposable.clear()
        super.onStop()
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragment) {
        super.preprocessPreferences(preferenceFragment)
        val distance = preferenceFragment.findPreference(resourceHelper.gs(R.string.key_smscommunicator_remotebolusmindistance)) as ValidatingEditTextPreference?
            ?: return
        val allowedNumbers = preferenceFragment.findPreference(resourceHelper.gs(R.string.key_smscommunicator_allowednumbers)) as EditTextPreference?
            ?: return
        if (!areMoreNumbers(allowedNumbers.text)) {
            distance.title = (resourceHelper.gs(R.string.smscommunicator_remotebolusmindistance)
                + ".\n"
                + resourceHelper.gs(R.string.smscommunicator_remotebolusmindistance_caveat))
            distance.isEnabled = false
        } else {
            distance.title = resourceHelper.gs(R.string.smscommunicator_remotebolusmindistance)
            distance.isEnabled = true
        }
        allowedNumbers.onPreferenceChangeListener = OnPreferenceChangeListener { _: Preference?, newValue: Any ->
            if (!areMoreNumbers(newValue as String)) {
                distance.text = (Constants.remoteBolusMinDistance / (60 * 1000L)).toString()
                distance.title = (resourceHelper.gs(R.string.smscommunicator_remotebolusmindistance)
                    + ".\n"
                    + resourceHelper.gs(R.string.smscommunicator_remotebolusmindistance_caveat))
                distance.isEnabled = false
            } else {
                distance.title = resourceHelper.gs(R.string.smscommunicator_remotebolusmindistance)
                distance.isEnabled = true
            }
            true
        }
    }

    override fun updatePreferenceSummary(pref: Preference) {
        super.updatePreferenceSummary(pref)
        if (pref is EditTextPreference) {
            val editTextPref = pref
            if (pref.getKey().contains("smscommunicator_allowednumbers") && (editTextPref.text == null || TextUtils.isEmpty(editTextPref.text.trim { it <= ' ' }))) {
                pref.setSummary(resourceHelper.gs(R.string.smscommunicator_allowednumbers_summary))
            }
        }
    }

    private fun processSettings(ev: EventPreferenceChange?) {
        if (ev == null || ev.isChanged(R.string.key_smscommunicator_allowednumbers)) {
            val settings = sp.getString(R.string.key_smscommunicator_allowednumbers, "")
            allowedNumbers.clear()
            val substrings = settings.split(";").toTypedArray()
            for (number in substrings) {
                val cleaned = number.replace("\\s+".toRegex(), "")
                allowedNumbers.add(cleaned)
                aapsLogger.debug(LTag.SMS, "Found allowed number: $cleaned")
            }
        }
    }

    fun isCommand(command: String, number: String): Boolean {
        var found = false
        commands.forEach { (k, _) ->
            if (k == command) found = true
        }
        return found || messageToConfirm?.requester?.phoneNumber == number
    }

    fun isAllowedNumber(number: String): Boolean {
        for (num in allowedNumbers) {
            if (num == number) return true
        }
        return false
    }

    fun handleNewData(intent: Intent) {
        val bundle = intent.extras ?: return
        val format = bundle.getString("format") ?: return
        val pdus = bundle["pdus"] as Array<*>
        for (pdu in pdus) {
            val message = SmsMessage.createFromPdu(pdu as ByteArray, format)
            processSms(Sms(message))
        }
    }

    fun processSms(receivedSms: Sms) {
        if (!isEnabled(PluginType.GENERAL)) {
            aapsLogger.debug(LTag.SMS, "Ignoring SMS. Plugin disabled.")
            return
        }
        if (!isAllowedNumber(receivedSms.phoneNumber)) {
            aapsLogger.debug(LTag.SMS, "Ignoring SMS from: " + receivedSms.phoneNumber + ". Sender not allowed")
            receivedSms.ignored = true
            messages.add(receivedSms)
            rxBus.send(EventSmsCommunicatorUpdateGui())
            return
        }
        val pump = configBuilderPlugin.activePump ?: return
        messages.add(receivedSms)
        aapsLogger.debug(LTag.SMS, receivedSms.toString())
        val splitted = receivedSms.text.split(Regex("\\s+")).toTypedArray()
        val remoteCommandsAllowed = sp.getBoolean(R.string.key_smscommunicator_remotecommandsallowed, false)
        if (splitted.isNotEmpty() && isCommand(splitted[0].toUpperCase(Locale.getDefault()), receivedSms.phoneNumber)) {
            when (splitted[0].toUpperCase(Locale.getDefault())) {
                "BG"         ->
                    if (splitted.size == 1) processBG(receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "LOOP"       ->
                    if (!remoteCommandsAllowed) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_remotecommandnotallowed)))
                    else if (splitted.size == 2 || splitted.size == 3) processLOOP(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "TREATMENTS" ->
                    if (splitted.size == 2) processTREATMENTS(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "NSCLIENT"   ->
                    if (splitted.size == 2) processNSCLIENT(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "PUMP"       ->
                    if (splitted.size == 1) processPUMP(receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "PROFILE"    ->
                    if (!remoteCommandsAllowed) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_remotecommandnotallowed)))
                    else if (splitted.size == 2 || splitted.size == 3) processPROFILE(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "BASAL"      ->
                    if (!remoteCommandsAllowed) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_remotecommandnotallowed)))
                    else if (splitted.size == 2 || splitted.size == 3) processBASAL(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "EXTENDED"   ->
                    if (!remoteCommandsAllowed) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_remotecommandnotallowed)))
                    else if (splitted.size == 2 || splitted.size == 3) processEXTENDED(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "BOLUS"      ->
                    if (!remoteCommandsAllowed) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_remotecommandnotallowed)))
                    else if (splitted.size == 2 && DateUtil.now() - lastRemoteBolusTime < Constants.remoteBolusMinDistance) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_remotebolusnotallowed)))
                    else if (splitted.size == 2 && pump.isSuspended) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.pumpsuspended)))
                    else if (splitted.size == 2 || splitted.size == 3) processBOLUS(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "CARBS"      ->
                    if (!remoteCommandsAllowed) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_remotecommandnotallowed)))
                    else if (splitted.size == 2 || splitted.size == 3) processCARBS(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "CAL"        ->
                    if (!remoteCommandsAllowed) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_remotecommandnotallowed)))
                    else if (splitted.size == 2) processCAL(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "TARGET"     ->
                    if (!remoteCommandsAllowed) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_remotecommandnotallowed)))
                    else if (splitted.size == 2) processTARGET(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "SMS"        ->
                    if (!remoteCommandsAllowed) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_remotecommandnotallowed)))
                    else if (splitted.size == 2) processSMS(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                "HELP"       ->
                    if (splitted.size == 1 || splitted.size == 2) processHELP(splitted, receivedSms)
                    else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                else         ->
                    if (messageToConfirm?.requester?.phoneNumber == receivedSms.phoneNumber) {
                        messageToConfirm?.action(splitted[0])
                        messageToConfirm = null
                    } else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_unknowncommand)))
            }
        }
        rxBus.send(EventSmsCommunicatorUpdateGui())
    }

    private fun processBG(receivedSms: Sms) {
        val actualBG = DatabaseHelper.actualBg()
        val lastBG = DatabaseHelper.lastBg()
        var reply = ""
        val units = profileFunction.getUnits()
        if (actualBG != null) {
            reply = resourceHelper.gs(R.string.sms_actualbg) + " " + actualBG.valueToUnitsToString(units) + ", "
        } else if (lastBG != null) {
            val agoMsec = System.currentTimeMillis() - lastBG.date
            val agoMin = (agoMsec / 60.0 / 1000.0).toInt()
            reply = resourceHelper.gs(R.string.sms_lastbg) + " " + lastBG.valueToUnitsToString(units) + " " + String.format(resourceHelper.gs(R.string.sms_minago), agoMin) + ", "
        }
        val glucoseStatus = GlucoseStatus.getGlucoseStatusData()
        if (glucoseStatus != null) reply += resourceHelper.gs(R.string.sms_delta) + " " + Profile.toUnitsString(glucoseStatus.delta, glucoseStatus.delta * Constants.MGDL_TO_MMOLL, units) + " " + units + ", "
        treatmentsPlugin.updateTotalIOBTreatments()
        val bolusIob = treatmentsPlugin.lastCalculationTreatments.round()
        treatmentsPlugin.updateTotalIOBTempBasals()
        val basalIob = treatmentsPlugin.lastCalculationTempBasals.round()
        val cobInfo = iobCobCalculatorPlugin.getCobInfo(false, "SMS COB")
        reply += (resourceHelper.gs(R.string.sms_iob) + " " + DecimalFormatter.to2Decimal(bolusIob.iob + basalIob.basaliob) + "U ("
            + resourceHelper.gs(R.string.sms_bolus) + " " + DecimalFormatter.to2Decimal(bolusIob.iob) + "U "
            + resourceHelper.gs(R.string.sms_basal) + " " + DecimalFormatter.to2Decimal(basalIob.basaliob) + "U), "
            + resourceHelper.gs(R.string.cob) + ": " + cobInfo.generateCOBString())
        sendSMS(Sms(receivedSms.phoneNumber, reply))
        receivedSms.processed = true
    }

    private fun processLOOP(splitted: Array<String>, receivedSms: Sms) {
        when (splitted[1].toUpperCase(Locale.getDefault())) {
            "DISABLE", "STOP" -> {
                if (loopPlugin.isEnabled(PluginType.LOOP)) {
                    loopPlugin.setPluginEnabled(PluginType.LOOP, false)
                    configBuilderPlugin.commandQueue.cancelTempBasal(true, object : Callback() {
                        override fun run() {
                            rxBus.send(EventRefreshOverview("SMS_LOOP_STOP"))
                            val replyText = resourceHelper.gs(R.string.smscommunicator_loophasbeendisabled) + " " +
                                resourceHelper.gs(if (result.success) R.string.smscommunicator_tempbasalcanceled else R.string.smscommunicator_tempbasalcancelfailed)
                            sendSMS(Sms(receivedSms.phoneNumber, replyText))
                        }
                    })
                } else
                    sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_loopisdisabled)))
                receivedSms.processed = true
            }

            "ENABLE", "START" -> {
                if (!loopPlugin.isEnabled(PluginType.LOOP)) {
                    loopPlugin.setPluginEnabled(PluginType.LOOP, true)
                    sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_loophasbeenenabled)))
                    rxBus.send(EventRefreshOverview("SMS_LOOP_START"))
                } else
                    sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_loopisenabled)))
                receivedSms.processed = true
            }

            "STATUS"          -> {
                val reply = if (loopPlugin.isEnabled(PluginType.LOOP)) {
                    if (loopPlugin.isSuspended()) String.format(resourceHelper.gs(R.string.loopsuspendedfor), loopPlugin.minutesToEndOfSuspend())
                    else resourceHelper.gs(R.string.smscommunicator_loopisenabled)
                } else
                    resourceHelper.gs(R.string.smscommunicator_loopisdisabled)
                sendSMS(Sms(receivedSms.phoneNumber, reply))
                receivedSms.processed = true
            }

            "RESUME"          -> {
                rxBus.send(EventRefreshOverview("SMS_LOOP_RESUME"))
                NSUpload.uploadOpenAPSOffline(0.0)
                sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_loopresumed)))
            }

            "SUSPEND"         -> {
                var duration = 0
                if (splitted.size == 3) duration = SafeParse.stringToInt(splitted[2])
                duration = Math.max(0, duration)
                duration = Math.min(180, duration)
                if (duration == 0) {
                    receivedSms.processed = true
                    sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_wrongduration)))
                    return
                } else {
                    val passCode = generatePasscode()
                    val reply = String.format(resourceHelper.gs(R.string.smscommunicator_suspendreplywithcode), duration, passCode)
                    receivedSms.processed = true
                    messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction(duration) {
                        override fun run() {
                            configBuilderPlugin.commandQueue.cancelTempBasal(true, object : Callback() {
                                override fun run() {
                                    if (result.success) {
                                        loopPlugin.suspendTo(System.currentTimeMillis() + anInteger() * 60L * 1000)
                                        NSUpload.uploadOpenAPSOffline(anInteger() * 60.toDouble())
                                        rxBus.send(EventRefreshOverview("SMS_LOOP_SUSPENDED"))
                                        val replyText = resourceHelper.gs(R.string.smscommunicator_loopsuspended) + " " +
                                            resourceHelper.gs(if (result.success) R.string.smscommunicator_tempbasalcanceled else R.string.smscommunicator_tempbasalcancelfailed)
                                        sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                                    } else {
                                        var replyText = resourceHelper.gs(R.string.smscommunicator_tempbasalcancelfailed)
                                        replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                        sendSMS(Sms(receivedSms.phoneNumber, replyText))
                                    }
                                }
                            })
                        }
                    })
                }
            }

            else              -> sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
        }
    }

    private fun processTREATMENTS(splitted: Array<String>, receivedSms: Sms) {
        if (splitted[1].toUpperCase(Locale.getDefault()) == "REFRESH") {
            treatmentsPlugin.service.resetTreatments()
            rxBus.send(EventNSClientRestart())
            sendSMS(Sms(receivedSms.phoneNumber, "TREATMENTS REFRESH SENT"))
            receivedSms.processed = true
        } else
            sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
    }

    private fun processNSCLIENT(splitted: Array<String>, receivedSms: Sms) {
        if (splitted[1].toUpperCase(Locale.getDefault()) == "RESTART") {
            rxBus.send(EventNSClientRestart())
            sendSMS(Sms(receivedSms.phoneNumber, "NSCLIENT RESTART SENT"))
            receivedSms.processed = true
        } else
            sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
    }

    private fun processHELP(splitted: Array<String>, receivedSms: Sms) {
        if (splitted.size == 1) {
            sendSMS(Sms(receivedSms.phoneNumber, commands.keys.toString().replace("[", "").replace("]", "")))
            receivedSms.processed = true
        } else if (isCommand(splitted[1].toUpperCase(Locale.getDefault()), receivedSms.phoneNumber)) {
            commands[splitted[1].toUpperCase(Locale.getDefault())]?.let {
                sendSMS(Sms(receivedSms.phoneNumber, it))
                receivedSms.processed = true
            }
        } else
            sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
    }

    private fun processPUMP(receivedSms: Sms) {
        configBuilderPlugin.commandQueue.readStatus("SMS", object : Callback() {
            override fun run() {
                val pump = configBuilderPlugin.activePump
                if (result.success) {
                    if (pump != null) {
                        val reply = pump.shortStatus(true)
                        sendSMS(Sms(receivedSms.phoneNumber, reply))
                    }
                } else {
                    val reply = resourceHelper.gs(R.string.readstatusfailed)
                    sendSMS(Sms(receivedSms.phoneNumber, reply))
                }
            }
        })
        receivedSms.processed = true
    }

    private fun processPROFILE(splitted: Array<String>, receivedSms: Sms) { // load profiles
        val anInterface = configBuilderPlugin.activeProfileInterface
        val store = anInterface.profile
        if (store == null) {
            sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.notconfigured)))
            receivedSms.processed = true
            return
        }
        val profileName = profileFunction.getProfileName()
        val list = store.getProfileList()
        if (splitted[1].toUpperCase(Locale.getDefault()) == "STATUS") {
            sendSMS(Sms(receivedSms.phoneNumber, profileName))
        } else if (splitted[1].toUpperCase(Locale.getDefault()) == "LIST") {
            if (list.isEmpty()) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.invalidprofile)))
            else {
                var reply = ""
                for (i in list.indices) {
                    if (i > 0) reply += "\n"
                    reply += (i + 1).toString() + ". "
                    reply += list[i]
                }
                sendSMS(Sms(receivedSms.phoneNumber, reply))
            }
        } else {
            val pindex = SafeParse.stringToInt(splitted[1])
            var percentage = 100
            if (splitted.size > 2) percentage = SafeParse.stringToInt(splitted[2])
            if (pindex > list.size) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
            else if (percentage == 0) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
            else if (pindex == 0) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
            else {
                val profile = store.getSpecificProfile(list[pindex - 1] as String)
                if (profile == null) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.noprofile)))
                else {
                    val passCode = generatePasscode()
                    val reply = String.format(resourceHelper.gs(R.string.smscommunicator_profilereplywithcode), list[pindex - 1], percentage, passCode)
                    receivedSms.processed = true
                    val finalPercentage = percentage
                    messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction(list[pindex - 1] as String, finalPercentage) {
                        override fun run() {
                            treatmentsPlugin.doProfileSwitch(store, list[pindex - 1] as String, 0, finalPercentage, 0, DateUtil.now())
                            sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.profileswitchcreated)))
                        }
                    })
                }
            }
        }
        receivedSms.processed = true
    }

    private fun processBASAL(splitted: Array<String>, receivedSms: Sms) {
        if (splitted[1].toUpperCase(Locale.getDefault()) == "CANCEL" || splitted[1].toUpperCase(Locale.getDefault()) == "STOP") {
            val passCode = generatePasscode()
            val reply = String.format(resourceHelper.gs(R.string.smscommunicator_basalstopreplywithcode), passCode)
            receivedSms.processed = true
            messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction() {
                override fun run() {
                    configBuilderPlugin.commandQueue.cancelTempBasal(true, object : Callback() {
                        override fun run() {
                            if (result.success) {
                                var replyText = resourceHelper.gs(R.string.smscommunicator_tempbasalcanceled)
                                replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                            } else {
                                var replyText = resourceHelper.gs(R.string.smscommunicator_tempbasalcancelfailed)
                                replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                sendSMS(Sms(receivedSms.phoneNumber, replyText))
                            }
                        }
                    })
                }
            })
        } else if (splitted[1].endsWith("%")) {
            var tempBasalPct = SafeParse.stringToInt(StringUtils.removeEnd(splitted[1], "%"))
            var duration = 30
            if (splitted.size > 2) duration = SafeParse.stringToInt(splitted[2])
            val profile = profileFunction.getProfile()
            if (profile == null) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.noprofile)))
            else if (tempBasalPct == 0 && splitted[1] != "0%") sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
            else if (duration == 0) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
            else {
                tempBasalPct = constraintChecker.applyBasalPercentConstraints(Constraint(tempBasalPct), profile).value()
                val passCode = generatePasscode()
                val reply = String.format(resourceHelper.gs(R.string.smscommunicator_basalpctreplywithcode), tempBasalPct, duration, passCode)
                receivedSms.processed = true
                messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction(tempBasalPct, duration) {
                    override fun run() {
                        configBuilderPlugin.commandQueue.tempBasalPercent(anInteger(), secondInteger(), true, profile, object : Callback() {
                            override fun run() {
                                if (result.success) {
                                    var replyText: String
                                    replyText = if (result.isPercent) String.format(resourceHelper.gs(R.string.smscommunicator_tempbasalset_percent), result.percent, result.duration) else String.format(resourceHelper.gs(R.string.smscommunicator_tempbasalset), result.absolute, result.duration)
                                    replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                    sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                                } else {
                                    var replyText = resourceHelper.gs(R.string.smscommunicator_tempbasalfailed)
                                    replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                    sendSMS(Sms(receivedSms.phoneNumber, replyText))
                                }
                            }
                        })
                    }
                })
            }
        } else {
            var tempBasal = SafeParse.stringToDouble(splitted[1])
            var duration = 30
            if (splitted.size > 2) duration = SafeParse.stringToInt(splitted[2])
            val profile = profileFunction.getProfile()
            if (profile == null) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.noprofile)))
            else if (tempBasal == 0.0 && splitted[1] != "0") sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
            else if (duration == 0) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
            else {
                tempBasal = constraintChecker.applyBasalConstraints(Constraint(tempBasal), profile).value()
                val passCode = generatePasscode()
                val reply = String.format(resourceHelper.gs(R.string.smscommunicator_basalreplywithcode), tempBasal, duration, passCode)
                receivedSms.processed = true
                messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction(tempBasal, duration) {
                    override fun run() {
                        configBuilderPlugin.commandQueue.tempBasalAbsolute(aDouble(), secondInteger(), true, profile, object : Callback() {
                            override fun run() {
                                if (result.success) {
                                    var replyText = if (result.isPercent) String.format(resourceHelper.gs(R.string.smscommunicator_tempbasalset_percent), result.percent, result.duration)
                                    else String.format(resourceHelper.gs(R.string.smscommunicator_tempbasalset), result.absolute, result.duration)
                                    replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                    sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                                } else {
                                    var replyText = resourceHelper.gs(R.string.smscommunicator_tempbasalfailed)
                                    replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                    sendSMS(Sms(receivedSms.phoneNumber, replyText))
                                }
                            }
                        })
                    }
                })
            }
        }
    }

    private fun processEXTENDED(splitted: Array<String>, receivedSms: Sms) {
        if (splitted[1].toUpperCase(Locale.getDefault()) == "CANCEL" || splitted[1].toUpperCase(Locale.getDefault()) == "STOP") {
            val passCode = generatePasscode()
            val reply = String.format(resourceHelper.gs(R.string.smscommunicator_extendedstopreplywithcode), passCode)
            receivedSms.processed = true
            messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction() {
                override fun run() {
                    configBuilderPlugin.commandQueue.cancelExtended(object : Callback() {
                        override fun run() {
                            if (result.success) {
                                var replyText = resourceHelper.gs(R.string.smscommunicator_extendedcanceled)
                                replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                            } else {
                                var replyText = resourceHelper.gs(R.string.smscommunicator_extendedcancelfailed)
                                replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                sendSMS(Sms(receivedSms.phoneNumber, replyText))
                            }
                        }
                    })
                }
            })
        } else if (splitted.size != 3) {
            sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
        } else {
            var extended = SafeParse.stringToDouble(splitted[1])
            val duration = SafeParse.stringToInt(splitted[2])
            extended = constraintChecker.applyExtendedBolusConstraints(Constraint(extended)).value()
            if (extended == 0.0 || duration == 0) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
            else {
                val passCode = generatePasscode()
                val reply = String.format(resourceHelper.gs(R.string.smscommunicator_extendedreplywithcode), extended, duration, passCode)
                receivedSms.processed = true
                messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction(extended, duration) {
                    override fun run() {
                        configBuilderPlugin.commandQueue.extendedBolus(aDouble(), secondInteger(), object : Callback() {
                            override fun run() {
                                if (result.success) {
                                    var replyText = String.format(resourceHelper.gs(R.string.smscommunicator_extendedset), aDouble, duration)
                                    replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                    sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                                } else {
                                    var replyText = resourceHelper.gs(R.string.smscommunicator_extendedfailed)
                                    replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                    sendSMS(Sms(receivedSms.phoneNumber, replyText))
                                }
                            }
                        })
                    }
                })
            }
        }
    }

    private fun processBOLUS(splitted: Array<String>, receivedSms: Sms) {
        var bolus = SafeParse.stringToDouble(splitted[1])
        val isMeal = splitted.size > 2 && splitted[2].equals("MEAL", ignoreCase = true)
        bolus = constraintChecker.applyBolusConstraints(Constraint(bolus)).value()
        if (splitted.size == 3 && !isMeal) {
            sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
        } else if (bolus > 0.0) {
            val passCode = generatePasscode()
            val reply = if (isMeal)
                String.format(resourceHelper.gs(R.string.smscommunicator_mealbolusreplywithcode), bolus, passCode)
            else
                String.format(resourceHelper.gs(R.string.smscommunicator_bolusreplywithcode), bolus, passCode)
            receivedSms.processed = true
            messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction(bolus) {
                override fun run() {
                    val detailedBolusInfo = DetailedBolusInfo()
                    detailedBolusInfo.insulin = aDouble()
                    detailedBolusInfo.source = Source.USER
                    configBuilderPlugin.commandQueue.bolus(detailedBolusInfo, object : Callback() {
                        override fun run() {
                            val resultSuccess = result.success
                            val resultBolusDelivered = result.bolusDelivered
                            configBuilderPlugin.commandQueue.readStatus("SMS", object : Callback() {
                                override fun run() {
                                    if (resultSuccess) {
                                        var replyText = if (isMeal)
                                            String.format(resourceHelper.gs(R.string.smscommunicator_mealbolusdelivered), resultBolusDelivered)
                                        else
                                            String.format(resourceHelper.gs(R.string.smscommunicator_bolusdelivered), resultBolusDelivered)
                                        replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                        lastRemoteBolusTime = DateUtil.now()
                                        if (isMeal) {
                                            profileFunction.getProfile()?.let { currentProfile ->
                                                var eatingSoonTTDuration = sp.getInt(R.string.key_eatingsoon_duration, Constants.defaultEatingSoonTTDuration)
                                                eatingSoonTTDuration =
                                                    if (eatingSoonTTDuration > 0) eatingSoonTTDuration
                                                    else Constants.defaultEatingSoonTTDuration
                                                var eatingSoonTT = sp.getDouble(R.string.key_eatingsoon_target, if (currentProfile.units == Constants.MMOL) Constants.defaultEatingSoonTTmmol else Constants.defaultEatingSoonTTmgdl)
                                                eatingSoonTT =
                                                    if (eatingSoonTT > 0) eatingSoonTT
                                                    else if (currentProfile.units == Constants.MMOL) Constants.defaultEatingSoonTTmmol
                                                    else Constants.defaultEatingSoonTTmgdl
                                                val tempTarget = TempTarget()
                                                    .date(System.currentTimeMillis())
                                                    .duration(eatingSoonTTDuration)
                                                    .reason(resourceHelper.gs(R.string.eatingsoon))
                                                    .source(Source.USER)
                                                    .low(Profile.toMgdl(eatingSoonTT, currentProfile.units))
                                                    .high(Profile.toMgdl(eatingSoonTT, currentProfile.units))
                                                treatmentsPlugin.addToHistoryTempTarget(tempTarget)
                                                val tt = if (currentProfile.units == Constants.MMOL) {
                                                    DecimalFormatter.to1Decimal(eatingSoonTT)
                                                } else DecimalFormatter.to0Decimal(eatingSoonTT)
                                                replyText += "\n" + String.format(resourceHelper.gs(R.string.smscommunicator_mealbolusdelivered_tt), tt, eatingSoonTTDuration)
                                            }
                                        }
                                        sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                                    } else {
                                        var replyText = resourceHelper.gs(R.string.smscommunicator_bolusfailed)
                                        replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                        sendSMS(Sms(receivedSms.phoneNumber, replyText))
                                    }
                                }
                            })
                        }
                    })
                }
            })
        } else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
    }

    private fun processCARBS(splitted: Array<String>, receivedSms: Sms) {
        var grams = SafeParse.stringToInt(splitted[1])
        var time = DateUtil.now()
        if (splitted.size > 2) {
            val seconds = DateUtil.toSeconds(splitted[2].toUpperCase(Locale.getDefault()))
            val midnight = MidnightTime.calc()
            if (seconds == 0 && (!splitted[2].startsWith("00:00") || !splitted[2].startsWith("12:00"))) {
                sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
                return
            }
            time = midnight + T.secs(seconds.toLong()).msecs()
        }
        grams = constraintChecker.applyCarbsConstraints(Constraint(grams)).value()
        if (grams == 0) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
        else {
            val passCode = generatePasscode()
            val reply = String.format(resourceHelper.gs(R.string.smscommunicator_carbsreplywithcode), grams, DateUtil.timeString(time), passCode)
            receivedSms.processed = true
            messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction(grams, time) {
                override fun run() {
                    val detailedBolusInfo = DetailedBolusInfo()
                    detailedBolusInfo.carbs = anInteger().toDouble()
                    detailedBolusInfo.date = secondLong()
                    configBuilderPlugin.commandQueue.bolus(detailedBolusInfo, object : Callback() {
                        override fun run() {
                            if (result.success) {
                                var replyText = String.format(resourceHelper.gs(R.string.smscommunicator_carbsset), anInteger)
                                replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                            } else {
                                var replyText = resourceHelper.gs(R.string.smscommunicator_carbsfailed)
                                replyText += "\n" + configBuilderPlugin.activePump?.shortStatus(true)
                                sendSMS(Sms(receivedSms.phoneNumber, replyText))
                            }
                        }
                    })
                }
            })
        }
    }

    private fun processTARGET(splitted: Array<String>, receivedSms: Sms) {
        val isMeal = splitted[1].equals("MEAL", ignoreCase = true)
        val isActivity = splitted[1].equals("ACTIVITY", ignoreCase = true)
        val isHypo = splitted[1].equals("HYPO", ignoreCase = true)
        val isStop = splitted[1].equals("STOP", ignoreCase = true) || splitted[1].equals("CANCEL", ignoreCase = true)
        if (isMeal || isActivity || isHypo) {
            val passCode = generatePasscode()
            val reply = String.format(resourceHelper.gs(R.string.smscommunicator_temptargetwithcode), splitted[1].toUpperCase(Locale.getDefault()), passCode)
            receivedSms.processed = true
            messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction() {
                override fun run() {
                    val units = profileFunction.getUnits()
                    var keyDuration = 0
                    var defaultTargetDuration = 0
                    var keyTarget = 0
                    var defaultTargetMMOL = 0.0
                    var defaultTargetMGDL = 0.0
                    if (isMeal) {
                        keyDuration = R.string.key_eatingsoon_duration
                        defaultTargetDuration = Constants.defaultEatingSoonTTDuration
                        keyTarget = R.string.key_eatingsoon_target
                        defaultTargetMMOL = Constants.defaultEatingSoonTTmmol
                        defaultTargetMGDL = Constants.defaultEatingSoonTTmgdl
                    } else if (isActivity) {
                        keyDuration = R.string.key_activity_duration
                        defaultTargetDuration = Constants.defaultActivityTTDuration
                        keyTarget = R.string.key_activity_target
                        defaultTargetMMOL = Constants.defaultActivityTTmmol
                        defaultTargetMGDL = Constants.defaultActivityTTmgdl
                    } else if (isHypo) {
                        keyDuration = R.string.key_hypo_duration
                        defaultTargetDuration = Constants.defaultHypoTTDuration
                        keyTarget = R.string.key_hypo_target
                        defaultTargetMMOL = Constants.defaultHypoTTmmol
                        defaultTargetMGDL = Constants.defaultHypoTTmgdl
                    }
                    var ttDuration = sp.getInt(keyDuration, defaultTargetDuration)
                    ttDuration = if (ttDuration > 0) ttDuration else defaultTargetDuration
                    var tt = sp.getDouble(keyTarget, if (units == Constants.MMOL) defaultTargetMMOL else defaultTargetMGDL)
                    tt = Profile.toCurrentUnits(tt)
                    tt = if (tt > 0) tt else if (units == Constants.MMOL) defaultTargetMMOL else defaultTargetMGDL
                    val tempTarget = TempTarget()
                        .date(System.currentTimeMillis())
                        .duration(ttDuration)
                        .reason(resourceHelper.gs(R.string.eatingsoon))
                        .source(Source.USER)
                        .low(Profile.toMgdl(tt, units))
                        .high(Profile.toMgdl(tt, units))
                    treatmentsPlugin.addToHistoryTempTarget(tempTarget)
                    val ttString = if (units == Constants.MMOL) DecimalFormatter.to1Decimal(tt) else DecimalFormatter.to0Decimal(tt)
                    val replyText = String.format(resourceHelper.gs(R.string.smscommunicator_tt_set), ttString, ttDuration)
                    sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                }
            })
        } else if (isStop) {
            val passCode = generatePasscode()
            val reply = String.format(resourceHelper.gs(R.string.smscommunicator_temptargetcancel), passCode)
            receivedSms.processed = true
            messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction() {
                override fun run() {
                    val tempTarget = TempTarget()
                        .source(Source.USER)
                        .date(DateUtil.now())
                        .duration(0)
                        .low(0.0)
                        .high(0.0)
                    treatmentsPlugin.addToHistoryTempTarget(tempTarget)
                    val replyText = String.format(resourceHelper.gs(R.string.smscommunicator_tt_canceled))
                    sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                }
            })
        } else
            sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
    }

    private fun processSMS(splitted: Array<String>, receivedSms: Sms) {
        val isStop = (splitted[1].equals("STOP", ignoreCase = true)
            || splitted[1].equals("DISABLE", ignoreCase = true))
        if (isStop) {
            val passCode = generatePasscode()
            val reply = String.format(resourceHelper.gs(R.string.smscommunicator_stopsmswithcode), passCode)
            receivedSms.processed = true
            messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction() {
                override fun run() {
                    sp.putBoolean(R.string.key_smscommunicator_remotecommandsallowed, false)
                    val replyText = String.format(resourceHelper.gs(R.string.smscommunicator_stoppedsms))
                    sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                }
            })
        } else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
    }

    private fun processCAL(splitted: Array<String>, receivedSms: Sms) {
        val cal = SafeParse.stringToDouble(splitted[1])
        if (cal > 0.0) {
            val passCode = generatePasscode()
            val reply = String.format(resourceHelper.gs(R.string.smscommunicator_calibrationreplywithcode), cal, passCode)
            receivedSms.processed = true
            messageToConfirm = AuthRequest(this, resourceHelper, receivedSms, reply, passCode, object : SmsAction(cal) {
                override fun run() {
                    val result = XdripCalibrations.sendIntent(aDouble)
                    if (result) sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_calibrationsent))) else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.smscommunicator_calibrationfailed)))
                }
            })
        } else sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
    }

    fun sendNotificationToAllNumbers(text: String): Boolean {
        var result = true
        for (i in allowedNumbers.indices) {
            val sms = Sms(allowedNumbers[i], text)
            result = result && sendSMS(sms)
        }
        return result
    }

    private fun sendSMSToAllNumbers(sms: Sms) {
        for (number in allowedNumbers) {
            sms.phoneNumber = number
            sendSMS(sms)
        }
    }

    fun sendSMS(sms: Sms): Boolean {
        val smsManager = SmsManager.getDefault()
        sms.text = stripAccents(sms.text)
        try {
            aapsLogger.debug(LTag.SMS, "Sending SMS to " + sms.phoneNumber + ": " + sms.text)
            if (sms.text.toByteArray().size <= 140) smsManager.sendTextMessage(sms.phoneNumber, null, sms.text, null, null)
            else {
                val parts = smsManager.divideMessage(sms.text)
                smsManager.sendMultipartTextMessage(sms.phoneNumber, null, parts,
                    null, null)
            }
            messages.add(sms)
        } catch (e: IllegalArgumentException) {
            return if (e.message == "Invalid message body") {
                val notification = Notification(Notification.INVALID_MESSAGE_BODY, resourceHelper.gs(R.string.smscommunicator_messagebody), Notification.NORMAL)
                rxBus.send(EventNewNotification(notification))
                false
            } else {
                val notification = Notification(Notification.INVALID_PHONE_NUMBER, resourceHelper.gs(R.string.smscommunicator_invalidphonennumber), Notification.NORMAL)
                rxBus.send(EventNewNotification(notification))
                false
            }
        } catch (e: SecurityException) {
            val notification = Notification(Notification.MISSING_SMS_PERMISSION, resourceHelper.gs(R.string.smscommunicator_missingsmspermission), Notification.NORMAL)
            rxBus.send(EventNewNotification(notification))
            return false
        }
        rxBus.send(EventSmsCommunicatorUpdateGui())
        return true
    }

    private fun generatePasscode(): String {
        val startChar1 = 'A'.toInt() // on iphone 1st char is uppercase :)
        var passCode = Character.toString((startChar1 + Math.random() * ('z' - 'a' + 1)).toChar())
        val startChar2: Int = if (Math.random() > 0.5) 'a'.toInt() else 'A'.toInt()
        passCode += Character.toString((startChar2 + Math.random() * ('z' - 'a' + 1)).toChar())
        val startChar3: Int = if (Math.random() > 0.5) 'a'.toInt() else 'A'.toInt()
        passCode += Character.toString((startChar3 + Math.random() * ('z' - 'a' + 1)).toChar())
        passCode = passCode.replace('l', 'k').replace('I', 'J')
        return passCode
    }

    private fun stripAccents(str: String): String {
        var s = str
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
        s = s.replace("[\\p{InCombiningDiacriticalMarks}]".toRegex(), "")
        return s
    }

    fun areMoreNumbers(allowednumbers: String?): Boolean {
        return allowednumbers?.let {
            var countNumbers = 0
            val substrings = it.split(";").toTypedArray()
            for (number in substrings) {
                var cleaned = number.replace(Regex("\\s+"), "")
                if (cleaned.length < 4) continue
                cleaned = cleaned.replace("+", "")
                cleaned = cleaned.replace("-", "")
                if (!cleaned.matches(Regex("[0-9]+"))) continue
                countNumbers++
            }
            countNumbers > 1
        } ?: false
    }
}