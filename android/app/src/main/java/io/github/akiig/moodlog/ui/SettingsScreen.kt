package io.github.akiig.moodlog.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import io.github.akiig.moodlog.PCLink
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import io.github.akiig.moodlog.Backup
import io.github.akiig.moodlog.BuildConfigVersion
import io.github.akiig.moodlog.Day
import io.github.akiig.moodlog.DoseLink
import io.github.akiig.moodlog.Reminder
import io.github.akiig.moodlog.trn
import io.github.akiig.moodlog.LocalApp
import io.github.akiig.moodlog.SyncException
import io.github.akiig.moodlog.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onPrivacy: () -> Unit, onSurveys: () -> Unit = {}, onWhatsNew: () -> Unit = {}) {
    val app = LocalApp.current
    val prefs = app.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pcs by remember { mutableStateOf(prefs.pcs) }
    var openPC by remember { mutableStateOf<String?>(null) }      // the PC whose fields are shown
    var showToken by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    fun changePC(id: String, change: (PCLink) -> PCLink) {
        prefs.updatePC(id, change); pcs = prefs.pcs
    }
    var autoSync by remember { mutableStateOf(prefs.autoSync) }
    var sendHealth by remember { mutableStateOf(prefs.sendHealth) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    var allowed by remember { mutableStateOf(false) }
    val healthAvailable = app.health.isAvailable()
    val ready = pcs.any { it.isConfigured }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()) { granted ->
        allowed = granted.containsAll(app.health.permissions)
    }
    LaunchedEffect(Unit) { allowed = app.health.hasPermissions() }

    // reminder
    var remind by remember { mutableStateOf(prefs.reminderOn && Reminder.canNotify(context)) }
    var remindAt by remember { mutableStateOf(Reminder.parse(prefs.reminderTime)) }
    var pickRemind by remember { mutableStateOf(false) }
    fun setRemind(on: Boolean) {
        remind = on; prefs.reminderOn = on; Reminder.schedule(context)
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> setRemind(ok) }

    // backup
    var backupName by remember { mutableStateOf(prefs.backupName) }
    var backupPlace by remember { mutableStateOf(prefs.backupUri.takeIf { it.isNotEmpty() }?.let { Backup.place(Uri.parse(it)) } ?: "") }
    var lastBackup by remember { mutableStateOf(prefs.lastBackup) }
    var backupBusy by remember { mutableStateOf(false) }
    var restoreMessage by remember { mutableStateOf("") }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri != null) {
            Backup.remember(context, uri, prefs)
            backupName = prefs.backupName; backupPlace = Backup.place(uri)
            scope.launch { backupBusy = true; app.backupNow(); lastBackup = prefs.lastBackup; backupBusy = false }
        }
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            restoreMessage = try {
                val state = withContext(Dispatchers.IO) { Backup.read(context, uri) }
                val n = app.store.absorb(state)
                trn("Restored: %@ change.", "Restored: %@ changes.", n)
            } catch (e: Exception) {
                tr("That file isn't a Sottovoce backup.")
            }
        }
    }
    var kindWord by remember { mutableStateOf(prefs.kindWord) }
    var weekCard by remember { mutableStateOf(prefs.weekCard) }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        LargeTitle(tr("Settings"))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Section(if (pcs.size > 1) tr("Your PCs") else tr("Your PC"),
                tr("In Sottovoce on your PC, open Phone sync and switch the receiver on. Use the address and token shown there. Your phone and PC need to be on the same Wi-Fi. With more than one PC, the phone syncs with each, and passes what you write on one PC to the others.")) {
                for (pc in pcs) {
                    val open = openPC == pc.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable {
                            openPC = if (open) null else pc.id
                            testResult = ""; showToken = false
                            prefs.pcs = prefs.pcs.filter { it.id == pc.id || it.server.isNotBlank() || it.token.isNotBlank() }
                            pcs = prefs.pcs
                        }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Computer, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pc.title, style = MaterialTheme.typography.bodyLarge)
                            Text(app.sync.pcStatus[pc.id] ?: if (pc.isConfigured) pc.server.trim() else tr("Address and token missing"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                            tint = MaterialTheme.colorScheme.outline)
                    }
                    if (open) {
                        Column(Modifier.padding(start = 36.dp)) {
                            FieldRow(tr("Name"), pc.name, { v -> changePC(pc.id) { it.copy(name = v) } },
                                tr("e.g. Mac or Living room PC"))
                            Line()
                            FieldRow(tr("Address"), pc.server, { v -> changePC(pc.id) { it.copy(server = v.trim()) } }, "http://pop-os.local:8765",
                                keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                            Line()
                            FieldRow(tr("Token"), pc.token, { v -> changePC(pc.id) { it.copy(token = v.trim()) } }, tr("From Phone sync on your PC"),
                                secret = !showToken,
                                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)) {
                                IconButton(onClick = { showToken = !showToken }) {
                                    Icon(if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        tr(if (showToken) "Hide token" else "Show token"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Line()
                            ActionRow(tr("Test connection"), enabled = pc.isConfigured, busy = testing) {
                                testing = true
                                scope.launch {
                                    testResult = try {
                                        withContext(Dispatchers.IO) { app.client.forPC(pc).ping() }
                                    } catch (e: SyncException) {
                                        e.message ?: ""
                                    } catch (e: Exception) {
                                        e.localizedMessage ?: e.javaClass.simpleName
                                    }
                                    testing = false
                                }
                            }
                            if (testResult.isNotEmpty()) {
                                Text(testResult, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                            }
                            Line()
                            ActionRow(tr("Send the last year of health data"), enabled = pc.isConfigured) {
                                changePC(pc.id) { it.copy(yearSent = false) }
                                val fresh = prefs.pcs.first { it.id == pc.id }
                                scope.launch { app.sync.sendHealth(listOf(fresh)) }
                            }
                            if (app.sync.healthStatus.isNotEmpty()) {
                                Text(app.sync.healthStatus, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                            }
                            Line()
                            ActionRow(tr("Remove this PC"), destructive = true) { confirmRemove = pc.id }
                        }
                    }
                    Line()
                }
                ActionRow(if (pcs.isEmpty()) tr("Add your PC") else tr("Add another PC")) {
                    val pc = PCLink()
                    prefs.pcs = prefs.pcs.filter { it.server.isNotBlank() || it.token.isNotBlank() } + pc
                    pcs = prefs.pcs
                    openPC = pc.id; testResult = ""; showToken = false
                }
            }
            confirmRemove?.let { id ->
                AlertDialog(onDismissRequest = { confirmRemove = null },
                    title = { Text(tr("Remove this PC?")) },
                    text = { Text(tr("Only the connection is removed: the diary stays on the phone and on the PC.")) },
                    confirmButton = {
                        TextButton(onClick = {
                            prefs.pcs = prefs.pcs.filter { it.id != id }; pcs = prefs.pcs
                            app.sync.pcStatus.remove(id)
                            if (openPC == id) openPC = null
                            confirmRemove = null
                        }) { Text(tr("Remove"), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(tr("Cancel")) } })
            }

            Section(tr("Sync"), tr("When automatic, Sottovoce syncs when you open it and a few seconds after each change. Entries made away from home are kept on the phone and sync when you're back.")) {
                SwitchRow(tr("Sync automatically"), autoSync) { autoSync = it; prefs.autoSync = it }
                Line()
                ActionRow(tr("Sync now"), enabled = ready, busy = app.sync.busy) { scope.launch { app.sync.run(app.store) } }
                Text(app.sync.status, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            }

            if (DoseLink.installed(context)) {
                var doseAllowed by remember { mutableStateOf(DoseLink.allowed(context)) }
                val askDose = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                    doseAllowed = ok; if (ok) app.readDose()
                }
                Section(tr("Dose on this phone"), tr("Your medicines and doses from Dose, on the day and in the doctor report. Sottovoce only reads them; nothing leaves the phone.")) {
                    if (doseAllowed) Text(tr("Sottovoce reads your medicines from Dose on this phone."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp))
                    else ActionRow(tr("Read my medicines from Dose")) { askDose.launch(DoseLink.PERMISSION) }
                }
            }

            Section(tr("Health Connect"), tr("Sends sleep, steps and resting heart rate from Health Connect to Sottovoce on your PC, so you can compare them with your mood. Your watch or fitness app needs to share them with Health Connect.")) {
                if (!healthAvailable) {
                    Text(tr("Health Connect isn't on this phone yet."), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 10.dp))
                    Line()
                    ActionRow(tr("Get Health Connect")) {
                        val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
                        } catch (e: Exception) {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")))
                        }
                    }
                } else {
                    if (!allowed) {
                        ActionRow(tr("Allow access to steps, heart rate and sleep")) { permissionLauncher.launch(app.health.permissions) }
                    } else {
                        Text(tr("Sottovoce can read your steps, resting heart rate and sleep."),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp))
                    }
                    Line()
                    SwitchRow(tr("Send with each sync (at most hourly)"), sendHealth) { sendHealth = it; prefs.sendHealth = it }
                    Line()
                    ActionRow(tr("Send the last 7 days now"), enabled = ready && allowed) { scope.launch { app.sync.sendHealth() } }
                    if (app.sync.healthStatus.isNotEmpty()) {
                        Text(app.sync.healthStatus, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
            }

            if (app.showQuestionnaires) {
                Section(tr("Questionnaires"), tr("PHQ-9 and GAD-7, with a gentle reminder every two weeks.")) {
                    LinkRow(tr("Questionnaires for your doctor"),
                        app.store.surveys.lastOrNull()?.let { Day.pretty(it.date, "d MMM") }, onClick = onSurveys)
                }
            }

            Section(tr("Reminder"), tr("Only on days you haven't logged a mood yet. No streaks, no guilt: just a gentle nudge.")) {
                SwitchRow(tr("Remind me once a day"), remind) { on ->
                    if (on && !Reminder.canNotify(context) && Build.VERSION.SDK_INT >= 33)
                        notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else setRemind(on)
                }
                if (remind) {
                    Line()
                    ValueRow(tr("At"), Day.timeString(remindAt)) { pickRemind = true }
                }
            }

            Section(tr("Backup"), if (backupName.isEmpty())
                tr("Pick a file once, for example in Google Drive, and Sottovoce keeps a copy of everything there after each change. The Drive app uploads it; Sottovoce itself never goes on the internet. You can restore it on any phone, iPhone included.")
                else (app.backupStatus.ifEmpty { restoreMessage }.ifEmpty { null })) {
                if (backupName.isEmpty()) {
                    ActionRow(tr("Choose a backup file…")) { pickBackup.launch(Backup.fileName()) }
                } else {
                    ValueRow(tr("File"), listOf(backupName, backupPlace).filter { it.isNotEmpty() }.joinToString(" · "))
                    Line()
                    ValueRow(tr("Last backup"), lastBackup.ifEmpty { tr("not yet") })
                    Line()
                    ActionRow(tr("Back up now"), busy = backupBusy) {
                        scope.launch { backupBusy = true; app.backupNow(); lastBackup = prefs.lastBackup; backupBusy = false }
                    }
                    Line()
                    ActionRow(tr("Choose another file…")) { pickBackup.launch(Backup.fileName()) }
                    Line()
                    ActionRow(tr("Stop backing up"), destructive = true) {
                        prefs.backupUri = ""; prefs.backupName = ""; backupName = ""; backupPlace = ""
                    }
                }
                Line()
                ActionRow(tr("Restore from a backup…")) { pickRestore.launch(arrayOf(Backup.MIME, "text/plain", "application/octet-stream")) }
                if (backupName.isEmpty() && restoreMessage.isNotEmpty()) {
                    Text(restoreMessage, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                }
            }

            Section(tr("Customize your experience"), footer = tr("Turn off what you don't need. Nothing is deleted.")) {
                CustomizeOptions()
            }

            Section(footer = tr("After a few very hard days close together, the Log shows a short, kind note with a helpline. It never tells anyone; you can hide it any time.")) {
                SwitchRow(tr("A kind word on hard days"), kindWord) { kindWord = it; prefs.kindWord = it }
            }

            Section(footer = tr("On Sunday evening and on Monday, a few calm words about your week: days logged, the usual mood, your best day. No scores.")) {
                SwitchRow(tr("Your week, gently"), weekCard) { weekCard = it; prefs.weekCard = it }
            }

            Section {
                LinkRow(tr("Show the tour")) { app.tour.start() }
                if (Build.VERSION.SDK_INT >= 33) {
                    Line()
                    LinkRow(tr("Language")) {
                        context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)))
                    }
                }
                Line()
                LinkRow(tr("Privacy"), onClick = onPrivacy)
                Line()
                LinkRow(tr("What's new"), onClick = onWhatsNew)
            }
            Section(tr("About"), tr("Sottovoce — a quiet mood diary. Formerly called Moodlog.")) {
                ValueRow(tr("Version"), BuildConfigVersion.name(context))
                Line()
                ValueRow(tr("Entries on this phone"), "${app.store.entries.size}")
                Line()
                ValueRow(tr("Events"), "${app.store.events.size}")
                Line()
                ValueRow(tr("Dreams"), "${app.store.dreams.size}")
                Line()
                ValueRow(tr("Questionnaires"), "${app.store.surveys.size}")
            }
            Spacer(Modifier.height(TabBarSpace))
        }
    }
    if (pickRemind) TimePickerDialog(remindAt, { pickRemind = false }) {
        remindAt = it; prefs.reminderTime = Day.timeString(it); Reminder.schedule(context)
    }
}
