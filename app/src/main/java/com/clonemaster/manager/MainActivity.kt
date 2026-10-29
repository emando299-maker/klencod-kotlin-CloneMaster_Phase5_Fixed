package com.clonemaster.manager

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class InstalledApp(val label: String, val packageName: String, val isSystem: Boolean)
data class CloneProfile(val sourceLabel: String, val packageName: String, val cloneName: String, val iconChoice: String)
data class ExtractedApk(val fileName: String, val path: String, val packageName: String)
data class ImportedApkInfo(val fileName: String, val path: String, val appLabel: String, val packageName: String, val versionName: String, val versionCode: Long)
data class WorkProfile(val sourcePackage: String, val targetPackage: String, val newLabel: String)

class MainActivity : ComponentActivity() {
    private var selectedApp by mutableStateOf<InstalledApp?>(null)
    private var refreshKey by mutableIntStateOf(0)
    private var pendingExport by mutableStateOf<File?>(null)
    private var importedApk by mutableStateOf<ImportedApkInfo?>(null)
    private var apkAuditReport by mutableStateOf<String?>(null)
    private var cloneStatus by mutableStateOf("")
    private var modifiedApk by mutableStateOf<File?>(null)
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri: Uri? ->
        val source = pendingExport
        if (uri != null && source != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(source).use { input -> input.copyTo(out) } }
                Toast.makeText(this, "تم تصدير ملف APK", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "فشل التصدير: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        pendingExport = null
    }
    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val dir = File(filesDir, "imported_apks").apply { mkdirs() }
                val out = File(dir, "import_${System.currentTimeMillis()}.apk")
                contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(out).use { output -> input.copyTo(output) } }
                    ?: throw IllegalStateException("تعذر قراءة الملف")
                val info = packageManager.getPackageArchiveInfo(out.absolutePath, PackageManager.GET_ACTIVITIES)
                    ?: throw IllegalArgumentException("الملف ليس APK صالحًا أو لا يمكن قراءته")
                info.applicationInfo?.sourceDir = out.absolutePath
                info.applicationInfo?.publicSourceDir = out.absolutePath
                val label = info.applicationInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: info.packageName
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                importedApk = ImportedApkInfo(out.name, out.absolutePath, label, info.packageName ?: "", info.versionName ?: "غير معروف", versionCode)
                apkAuditReport = null
                refreshKey++
                Toast.makeText(this, "تم فحص APK بنجاح", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر فحص APK: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CloneMasterScreen(
                        apps = remember(refreshKey) { loadLaunchableApps() },
                        selectedApp = selectedApp,
                        profiles = loadProfiles(),
                        extracted = loadExtracted(),
                        importedApk = importedApk,
                        apkAuditReport = apkAuditReport,
                        onAuditApk = { item -> apkAuditReport = auditApk(item) },
                        cloneStatus = cloneStatus,
                        modifiedApk = modifiedApk,
                        onCreateClone = { item, target -> createModifiedApk(item, target) },
                        workProfiles = loadWorkProfiles(),
                        onSaveWorkProfile = { source, target, label -> saveWorkProfile(source, target, label); refreshKey++ },
                        onAppClick = { selectedApp = it },
                        onBackToList = { selectedApp = null },
                        onImportApk = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                        onExtract = { app -> val file = extractApk(app); refreshKey++; file },
                        onExport = { file -> pendingExport = file; exportPicker.launch(file.name) },
                        onExportModified = { file -> pendingExport = file; exportPicker.launch(file.name) },
                        onSaveProfile = { profile -> saveProfile(profile); refreshKey++ },
                        onDeleteProfile = { profile -> deleteProfile(profile); refreshKey++ },
                        onDeleteExtracted = { item -> File(item.path).delete(); refreshKey++ }
                    )
                }
            }
        }
    }

    private fun createModifiedApk(item: ImportedApkInfo, targetPackage: String) {
        cloneStatus = "جاري تعديل Manifest وإعادة توقيع APK…"
        modifiedApk = null
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ApkCloneEngine.createClone(this@MainActivity, File(item.path), targetPackage.trim())
                }
                modifiedApk = result.output
                cloneStatus = "تم إنشاء APK معدل وتوقيعه. المعرّف: ${result.targetPackage}. جرّب تثبيته؛ نجاح البناء لا يضمن تشغيل كل التطبيقات."
                Toast.makeText(this@MainActivity, "تم إنشاء APK معدل", Toast.LENGTH_LONG).show()
                refreshKey++
            } catch (e: Exception) {
                cloneStatus = "فشل التعديل: ${e.message ?: "خطأ غير معروف"}"
                Toast.makeText(this@MainActivity, cloneStatus, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadLaunchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).mapNotNull { resolve ->
            val info = resolve.activityInfo?.applicationInfo ?: return@mapNotNull null
            InstalledApp(packageManager.getApplicationLabel(info).toString(), info.packageName,
                (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    private fun extractApk(app: InstalledApp): File? {
        return try {
            val info = packageManager.getApplicationInfo(app.packageName, 0)
            val dir = File(filesDir, "extracted_apks").apply { mkdirs() }
            val out = File(dir, "${safeFileName(app.label)}_${System.currentTimeMillis()}.apk")
            FileInputStream(info.sourceDir).use { input -> FileOutputStream(out).use { output -> input.copyTo(output) } }
            // Save only the base APK. Apps delivered as split APKs may not install from this file alone.
            Toast.makeText(this, "تم استخراج APK الأساسي. قد يحتاج التطبيق إلى ملفات split إضافية.", Toast.LENGTH_LONG).show()
            out
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر استخراج APK: ${e.message}", Toast.LENGTH_LONG).show(); null
        }
    }
    private fun auditApk(item: ImportedApkInfo): String {
        return try {
            val zip = ZipFile(item.path)
            zip.use { z ->
                val names = z.entries().asSequence().map { it.name }.toList()
                val dex = names.filter { it.matches(Regex("classes(\\d*)\\.dex")) }.sorted()
                val nativeLibs = names.filter { it.startsWith("lib/") && it.endsWith(".so") }
                val hasManifest = names.contains("AndroidManifest.xml")
                val hasResources = names.contains("resources.arsc")
                val v1Sigs = names.filter { it.startsWith("META-INF/") && (it.endsWith(".RSA", true) || it.endsWith(".DSA", true) || it.endsWith(".EC", true) || it.endsWith(".SF", true)) }
                val splitMarkers = names.filter { it.startsWith("split_") || it.startsWith("META-INF/com/android/build/gradle/app-metadata") }
                buildString {
                    appendLine("نتيجة فحص بنية APK")
                    appendLine("ملف: ${item.fileName}")
                    appendLine("AndroidManifest.xml: ${if (hasManifest) "موجود" else "غير موجود — الملف قد لا يكون APK صالحًا"}")
                    appendLine("ملفات DEX: ${dex.size}${if (dex.isNotEmpty()) " (${dex.joinToString(", ")})" else ""}")
                    appendLine("resources.arsc: ${if (hasResources) "موجود" else "غير موجود"}")
                    appendLine("مكتبات native: ${nativeLibs.size}")
                    appendLine("ملفات توقيع V1 الظاهرة داخل META-INF: ${v1Sigs.size}")
                    appendLine("علامات split داخل الأرشيف: ${splitMarkers.size}")
                    appendLine()
                    appendLine("الخطوة التالية: يلزم فك/تعديل Manifest والموارد حسب الحاجة، ثم إعادة بناء الملف وتوقيعه. هذا الفحص لا يغيّر APK ولا يؤكد صلاحية توقيعه V2/V3.")
                }
            }
        } catch (e: Exception) {
            "تعذر فحص الأرشيف: ${e.message ?: "خطأ غير معروف"}"
        }
    }
    private fun safeFileName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(50).ifBlank { "app" }
    private fun loadWorkProfiles(): List<WorkProfile> {
        val raw = getSharedPreferences("cm", MODE_PRIVATE).getString("work_profiles", "[]") ?: "[]"
        return try {
            val list = JSONArray(raw)
            (0 until list.length()).map { i ->
                val item = list.getJSONObject(i)
                WorkProfile(item.optString("sourcePackage"), item.optString("targetPackage"), item.optString("newLabel"))
            }
        } catch (_: Exception) { emptyList() }
    }
    private fun saveWorkProfile(sourcePackage: String, targetPackage: String, label: String) {
        val prefs = getSharedPreferences("cm", MODE_PRIVATE)
        val list = try { JSONArray(prefs.getString("work_profiles", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val next = JSONArray()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.optString("sourcePackage") != sourcePackage) next.put(item)
        }
        next.put(JSONObject().put("sourcePackage", sourcePackage).put("targetPackage", targetPackage).put("newLabel", label))
        prefs.edit().putString("work_profiles", next.toString()).apply()
        Toast.makeText(this, "تم حفظ خطة التعديل فقط. لم يتم تغيير APK نفسه بعد.", Toast.LENGTH_LONG).show()
    }
    private fun loadProfiles(): List<CloneProfile> {
        val raw = getSharedPreferences("cm", MODE_PRIVATE).getString("profiles", "[]") ?: "[]"
        return try { val a = JSONArray(raw); (0 until a.length()).map { val o = a.getJSONObject(it); CloneProfile(o.getString("sourceLabel"), o.getString("packageName"), o.getString("cloneName"), o.getString("iconChoice")) } } catch (_: Exception) { emptyList() }
    }
    private fun saveProfile(p: CloneProfile) {
        val list = loadProfiles().filterNot { it.packageName == p.packageName && it.cloneName == p.cloneName }.toMutableList(); list.add(p)
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("sourceLabel", it.sourceLabel).put("packageName", it.packageName).put("cloneName", it.cloneName).put("iconChoice", it.iconChoice)) }
        getSharedPreferences("cm", MODE_PRIVATE).edit().putString("profiles", a.toString()).apply()
        Toast.makeText(this, "تم حفظ إعدادات النسخة فقط؛ لم يتم إنشاء APK مستقل", Toast.LENGTH_LONG).show()
    }
    private fun deleteProfile(p: CloneProfile) {
        val a = JSONArray(); loadProfiles().filterNot { it == p }.forEach { a.put(JSONObject().put("sourceLabel", it.sourceLabel).put("packageName", it.packageName).put("cloneName", it.cloneName).put("iconChoice", it.iconChoice)) }
        getSharedPreferences("cm", MODE_PRIVATE).edit().putString("profiles", a.toString()).apply()
    }
    private fun loadExtracted(): List<ExtractedApk> = File(filesDir, "extracted_apks").listFiles()?.filter { it.isFile && it.extension.equals("apk", true) }?.sortedByDescending { it.lastModified() }?.map { ExtractedApk(it.name, it.absolutePath, "") } ?: emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloneMasterScreen(
    apps: List<InstalledApp>, selectedApp: InstalledApp?, profiles: List<CloneProfile>, extracted: List<ExtractedApk>,
    importedApk: ImportedApkInfo?, apkAuditReport: String?, onAuditApk: (ImportedApkInfo) -> Unit, cloneStatus: String, modifiedApk: File?, onCreateClone: (ImportedApkInfo, String) -> Unit, workProfiles: List<WorkProfile>, onSaveWorkProfile: (String, String, String) -> Unit,
    onAppClick: (InstalledApp) -> Unit, onBackToList: () -> Unit, onImportApk: () -> Unit,
    onExtract: (InstalledApp) -> File?, onExport: (File) -> Unit, onExportModified: (File) -> Unit, onSaveProfile: (CloneProfile) -> Unit,
    onDeleteProfile: (CloneProfile) -> Unit, onDeleteExtracted: (ExtractedApk) -> Unit
) {
    var showSystem by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var targetPackage by remember(importedApk?.path) { mutableStateOf(importedApk?.packageName?.let { "$it.clone" } ?: "") }
    var targetLabel by remember(importedApk?.path) { mutableStateOf(importedApk?.appLabel?.let { "$it نسخة" } ?: "") }
    var configError by remember(importedApk?.path) { mutableStateOf("") }
    if (selectedApp != null) {
        var cloneName by remember(selectedApp.packageName) { mutableStateOf("${selectedApp.label} (نسخة)") }
        var iconChoice by remember(selectedApp.packageName) { mutableStateOf("الأصلية") }
        var extractedFile by remember(selectedApp.packageName) { mutableStateOf<File?>(null) }
        Scaffold(topBar = { TopAppBar(title = { Text("إعداد النسخة") }, navigationIcon = { TextButton(onClick = onBackToList) { Text("رجوع") } }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(selectedApp.label, style = MaterialTheme.typography.headlineSmall)
                Text("معرّف الأصل: ${selectedApp.packageName}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = cloneName, onValueChange = { cloneName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("اسم النسخة المقترح") }, singleLine = true)
                Text("الأيقونة المقترحة")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = iconChoice == "الأصلية", onClick = { iconChoice = "الأصلية" }, label = { Text("الأصلية") })
                    FilterChip(selected = iconChoice == "Android", onClick = { iconChoice = "Android" }, label = { Text("Android") })
                }
                Button(onClick = { onSaveProfile(CloneProfile(selectedApp.label, selectedApp.packageName, cloneName.ifBlank { "${selectedApp.label} (نسخة)" }, iconChoice)) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("حفظ إعدادات النسخة") }
                Button(onClick = { extractedFile = onExtract(selectedApp) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("استخراج APK الأساسي") }
                extractedFile?.let { file -> OutlinedButton(onClick = { onExport(file) }, modifier = Modifier.fillMaxWidth()) { Text("تصدير APK إلى مجلد تختاره") } }
                Text("تنبيه: استخراج APK وحفظ الإعدادات يعملان كميزات أولية. إعادة بناء Manifest والموارد وتغيير package ID وإعادة التوقيع والتثبيت كنسخة مستقلة غير مفعّلة بعد.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    val filtered = apps.filter { (showSystem || !it.isSystem) && (it.label.contains(query, true) || it.packageName.contains(query, true)) }
    Scaffold(topBar = { TopAppBar(title = { Text("Clone Master") }, navigationIcon = { Icon(Icons.Default.Android, null, Modifier.padding(start = 16.dp)) }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("مدير التطبيقات", style = MaterialTheme.typography.headlineSmall) }
            item { Text("المرحلة الرابعة: استخراج APK، حفظ خطة النسخة، وفحص بنية APK قبل التعديل.", style = MaterialTheme.typography.bodyMedium) }
            item { OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ابحث عن تطبيق") }, singleLine = true) }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = showSystem, onCheckedChange = { showSystem = it }); Text("إظهار تطبيقات النظام") } }
            item { OutlinedButton(onClick = onImportApk, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("اختيار APK لفحصه") } }
            if (importedApk != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("نتيجة فحص APK", style = MaterialTheme.typography.titleMedium)
                            Text("الاسم الأصلي: ${importedApk.appLabel}")
                            Text("Package Name الأصلي: ${importedApk.packageName}", style = MaterialTheme.typography.bodySmall)
                            Text("الإصدار: ${importedApk.versionName} (${importedApk.versionCode})", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { onAuditApk(importedApk) }, modifier = Modifier.fillMaxWidth()) { Text("فحص بنية APK قبل التعديل") }
                            apkAuditReport?.let { report -> Card(Modifier.fillMaxWidth()) { Text(report, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
                            OutlinedTextField(value = targetLabel, onValueChange = { targetLabel = it; configError = "" }, modifier = Modifier.fillMaxWidth(), label = { Text("اسم النسخة الجديد") }, singleLine = true)
                            OutlinedTextField(value = targetPackage, onValueChange = { targetPackage = it; configError = "" }, modifier = Modifier.fillMaxWidth(), label = { Text("Package Name الجديد") }, supportingText = { Text("مثال: com.example.game.clone") }, singleLine = true)
                            if (configError.isNotBlank()) Text(configError, color = MaterialTheme.colorScheme.error)
                            Button(onClick = {
                                val valid = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(targetPackage.trim())
                                when {
                                    targetLabel.isBlank() -> configError = "اكتب اسم النسخة أولًا."
                                    !valid -> configError = "Package Name غير صحيح. استخدم حروفًا وأرقامًا ونقاطًا مثل com.example.app.clone"
                                    targetPackage.trim() == importedApk.packageName -> configError = "اختار Package Name مختلف عن الأصل."
                                    else -> { onSaveWorkProfile(importedApk.packageName, targetPackage.trim(), targetLabel.trim()); configError = "تم حفظ الخطة." }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("حفظ خطة النسخة") }
                            Button(onClick = {
                                val valid = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(targetPackage.trim())
                                when {
                                    !valid -> configError = "Package Name غير صحيح."
                                    targetPackage.trim() == importedApk.packageName -> configError = "اختار Package Name مختلف عن الأصل."
                                    else -> { configError = ""; onCreateClone(importedApk, targetPackage.trim()) }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Android, null); Spacer(Modifier.width(8.dp)); Text("إنشاء APK معدل وتوقيعه") }
                            if (cloneStatus.isNotBlank()) Text(cloneStatus, color = if (cloneStatus.startsWith("فشل")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                            modifiedApk?.let { file ->
                                Text("الملف الناتج: ${file.name}", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { onExportModified(file) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("تصدير APK المعدل") }
                            }
                            Text("تجريبي: يغيّر اسم الحزمة في Manifest ويزيل توقيع الأصل ثم يوقّع الملف من جديد. لا يغيّر اسم العرض أو الأيقونة، وقد لا تعمل التطبيقات التي تحتوي مراجع للحزمة داخل DEX أو تحققًا من سلامة التطبيق. ملفات split غير مدعومة في هذه المرحلة.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Text("خطط النسخ المحفوظة (${workProfiles.size})", style = MaterialTheme.typography.titleMedium) }
            if (workProfiles.isEmpty()) item { Text("لسه مفيش خطط محفوظة.", style = MaterialTheme.typography.bodySmall) }
            items(workProfiles, key = { "work_${it.sourcePackage}" }) { profile ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.newLabel, style = MaterialTheme.typography.titleSmall)
                        Text("الأصل: ${profile.sourcePackage}", style = MaterialTheme.typography.bodySmall)
                        Text("المعرّف المقترح: ${profile.targetPackage}", style = MaterialTheme.typography.bodySmall)
                        Text("خطة محفوظة فقط — لم يتم تعديل APK", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { Text("التطبيقات (${filtered.size})", style = MaterialTheme.typography.titleMedium) }
            items(filtered, key = { it.packageName }) { app -> Card(onClick = { onAppClick(app) }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Android, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(app.label, style = MaterialTheme.typography.titleSmall); Text(app.packageName, style = MaterialTheme.typography.bodySmall); if (app.isSystem) Text("تطبيق نظام", style = MaterialTheme.typography.labelSmall) } } } }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("إعدادات النسخ المحفوظة (${profiles.size})", style = MaterialTheme.typography.titleMedium) }
            if (profiles.isEmpty()) item { Text("لا توجد إعدادات محفوظة بعد.", style = MaterialTheme.typography.bodySmall) }
            items(profiles, key = { it.packageName + it.cloneName }) { p -> Card { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(p.cloneName); Text("من: ${p.sourceLabel} · ${p.iconChoice}", style = MaterialTheme.typography.bodySmall); Text("إعدادات فقط — ليس APK مستقلاً", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }; IconButton(onClick = { onDeleteProfile(p) }) { Icon(Icons.Default.Delete, "حذف الإعداد") } } } }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("ملفات APK المستخرجة (${extracted.size})", style = MaterialTheme.typography.titleMedium) }
            if (extracted.isEmpty()) item { Text("لم يتم استخراج ملفات بعد.", style = MaterialTheme.typography.bodySmall) }
            items(extracted, key = { it.path }) { f -> Card { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(f.fileName); Text("محفوظ داخل مساحة CloneMaster", style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = { onExport(File(f.path)) }) { Icon(Icons.Default.FileOpen, "تصدير") }; IconButton(onClick = { onDeleteExtracted(f) }) { Icon(Icons.Default.Delete, "حذف الملف") } } } }
        }
    }
}