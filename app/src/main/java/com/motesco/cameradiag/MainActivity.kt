package com.motesco.cameradiag

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var reportText: TextView
    private lateinit var runTestButton: Button
    private lateinit var shareButton: Button
    private lateinit var cameraManager: CameraManager

    private var lastReport: String = ""

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                runFullDiagnostic()
            } else {
                reportText.text = "تم رفض إذن الكاميرا. التطبيق يحتاج إذن الكاميرا لتنفيذ الفحص."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        reportText = findViewById(R.id.reportText)
        runTestButton = findViewById(R.id.runTestButton)
        shareButton = findViewById(R.id.shareButton)
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        runTestButton.setOnClickListener {
            if (hasCameraPermission()) {
                runFullDiagnostic()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        shareButton.setOnClickListener {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "تقرير فحص الكاميرا المزدوجة")
                putExtra(Intent.EXTRA_TEXT, lastReport)
            }
            startActivity(Intent.createChooser(sendIntent, "مشاركة التقرير"))
        }
    }

    private fun hasCameraPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ---------- Static characteristics report ----------

    private data class CamInfo(
        val id: String,
        val facing: String,
        val hardwareLevel: String,
        val capabilities: List<String>,
        val maxOutputSize: Size?,
        val isLogicalMultiCamera: Boolean
    )

    private fun facingName(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "أمامية"
        CameraCharacteristics.LENS_FACING_BACK -> "خلفية"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "خارجية"
        else -> "غير معروف"
    }

    private fun hwLevelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY (قديم/محدود)"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "غير معروف"
    }

    private fun collectCamInfo(): List<CamInfo> {
        val list = mutableListOf<CamInfo>()
        for (id in cameraManager.cameraIdList) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = facingName(chars.get(CameraCharacteristics.LENS_FACING))
                val hwLevel = hwLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val capsNames = caps?.map { capabilityName(it) } ?: emptyList()
                val isLogical = caps?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                ) == true
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val maxSize = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    ?.maxByOrNull { it.width.toLong() * it.height }

                list.add(CamInfo(id, facing, hwLevel, capsNames, maxSize, isLogical))
            } catch (e: Exception) {
                list.add(CamInfo(id, "خطأ في القراءة: ${e.message}", "-", emptyList(), null, false))
            }
        }
        return list
    }

    private fun capabilityName(cap: Int): String = when (cap) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        else -> "CAP_$cap"
    }

    // ---------- Formal concurrent-camera API (Android 11 / API 30+) ----------

    private fun formalConcurrentSupport(camInfos: List<CamInfo>): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < 30) {
            return false to "غير مدعوم رسمياً (Android ${Build.VERSION.RELEASE} أقدم من Android 11)"
        }
        return try {
            val combos = cameraManager.concurrentCameraIds
            if (combos.isEmpty()) {
                false to "النظام لا يعلن عن أي تركيبة كاميرات متزامنة مدعومة رسمياً"
            } else {
                val frontIds = camInfos.filter { it.facing == "أمامية" }.map { it.id }.toSet()
                val backIds = camInfos.filter { it.facing == "خلفية" }.map { it.id }.toSet()
                val hasFrontBackCombo = combos.any { combo ->
                    combo.any { it in frontIds } && combo.any { it in backIds }
                }
                val combosText = combos.joinToString("\n") { "  [${it.joinToString(", ")}]" }
                val verdict = if (hasFrontBackCombo)
                    "نعم، يوجد تركيبة رسمية تجمع كاميرا أمامية وخلفية معاً ✅"
                else
                    "التركيبات المدعومة رسمياً لا تجمع بين أمامية وخلفية معاً ⚠️"
                hasFrontBackCombo to "$verdict\nالتركيبات المعلنة من النظام:\n$combosText"
            }
        } catch (e: Exception) {
            false to "تعذر قراءة concurrentCameraIds: ${e.message}"
        }
    }

    // ---------- Real functional test: try opening front+back at once ----------

    private fun runFullDiagnostic() {
        reportText.text = "جاري الفحص...\n"
        shareButton.isEnabled = false
        runTestButton.isEnabled = false

        val camInfos = collectCamInfo()
        val (formalSupported, formalText) = formalConcurrentSupport(camInfos)

        val frontId = camInfos.firstOrNull { it.facing == "أمامية" }?.id
        val backId = camInfos.firstOrNull { it.facing == "خلفية" }?.id

        val header = buildString {
            appendLine("===== تقرير فحص الجهاز =====")
            appendLine("الشركة المصنعة: ${Build.MANUFACTURER}")
            appendLine("الموديل: ${Build.MODEL} (${Build.DEVICE})")
            appendLine("إصدار أندرويد: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("عدد الكاميرات المكتشفة: ${camInfos.size}")
            appendLine()
            appendLine("----- تفاصيل كل كاميرا -----")
            for (c in camInfos) {
                appendLine("كاميرا [${c.id}] - الاتجاه: ${c.facing}")
                appendLine("  مستوى الدعم: ${c.hardwareLevel}")
                appendLine("  متعددة منطقياً (LOGICAL_MULTI_CAMERA): ${if (c.isLogicalMultiCamera) "نعم" else "لا"}")
                if (c.maxOutputSize != null) {
                    appendLine("  أعلى دقة: ${c.maxOutputSize.width}x${c.maxOutputSize.height}")
                }
                appendLine("  القدرات: ${c.capabilities.joinToString(", ")}")
            }
            appendLine()
            appendLine("----- دعم واجهة الكاميرا المتزامنة الرسمية (Android 11+) -----")
            appendLine(formalText)
            appendLine()
            appendLine("----- الاختبار الفعلي: فتح الكاميرتين معاً -----")
        }

        if (frontId == null || backId == null) {
            val full = header + "لا يمكن إجراء الاختبار: الجهاز لا يحتوي على كاميرا أمامية وخلفية معاً.\n"
            finishReport(full, false)
            return
        }

        testOpenBothCameras(frontId, backId) { resultText, actualSuccess ->
            val full = buildString {
                append(header)
                appendLine(resultText)
                appendLine()
                appendLine("===== الخلاصة النهائية =====")
                if (actualSuccess) {
                    appendLine("✅ الجهاز قادر فعلياً على فتح الكاميرا الأمامية والخلفية في نفس الوقت.")
                    appendLine("يمكن بناء تطبيق التسجيل المتزامن (PiP) بأمان على هذا الجهاز.")
                } else {
                    appendLine("❌ الجهاز لم ينجح في فتح الكاميرتين معاً في هذا الاختبار.")
                    appendLine("قد نحتاج لطريقة بديلة (مثل التبديل السريع بين الكاميرتين، أو التسجيل المنفصل ثم الدمج).")
                }
                if (formalSupported != actualSuccess) {
                    appendLine()
                    appendLine("ملاحظة: النتيجة الرسمية (${if (formalSupported) "مدعوم" else "غير مدعوم"}) تختلف عن نتيجة الاختبار الفعلي - الاعتماد على الاختبار الفعلي أدق.")
                }
            }
            finishReport(full, true)
        }
    }

    private fun finishReport(text: String, enableShare: Boolean) {
        lastReport = text
        reportText.text = text
        shareButton.isEnabled = enableShare
        runTestButton.isEnabled = true
    }

    private fun testOpenBothCameras(
        frontId: String,
        backId: String,
        onDone: (resultText: String, actualSuccess: Boolean) -> Unit
    ) {
        val thread = HandlerThread("CamConcurrentTest").apply { start() }
        val handler = Handler(thread.looper)

        var frontDevice: CameraDevice? = null
        var backDevice: CameraDevice? = null
        var frontStatus = "لم يكتمل (انتهت المهلة)"
        var backStatus = "لم يكتمل (انتهت المهلة)"
        var frontDone = false
        var backDone = false
        var finished = false

        fun cleanupAndReport() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            try { frontDevice?.close() } catch (_: Exception) {}
            try { backDevice?.close() } catch (_: Exception) {}
            thread.quitSafely()

            val success = frontStatus.contains("نجح") && backStatus.contains("نجح")
            val text = buildString {
                appendLine("الكاميرا الأمامية [$frontId]: $frontStatus")
                appendLine("الكاميرا الخلفية [$backId]: $backStatus")
            }
            runOnUiThread { onDone(text, success) }
        }

        fun maybeFinish() {
            if (frontDone && backDone) cleanupAndReport()
        }

        val timeoutRunnable = Runnable {
            if (!frontDone) { frontStatus = "فشل - انتهت المهلة (5 ثواني) بدون استجابة"; frontDone = true }
            if (!backDone) { backStatus = "فشل - انتهت المهلة (5 ثواني) بدون استجابة"; backDone = true }
            cleanupAndReport()
        }
        handler.postDelayed(timeoutRunnable, 5000)

        try {
            cameraManager.openCamera(frontId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    frontDevice = device
                    frontStatus = "نجح الفتح ✅"
                    frontDone = true
                    maybeFinish()
                }
                override fun onDisconnected(device: CameraDevice) {
                    frontStatus = "انقطع الاتصال بعد الفتح"
                    frontDone = true
                    maybeFinish()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    frontStatus = "فشل - كود الخطأ $error (${cameraErrorName(error)})"
                    frontDone = true
                    maybeFinish()
                }
            }, handler)
        } catch (e: Exception) {
            frontStatus = "استثناء: ${e.javaClass.simpleName} - ${e.message}"
            frontDone = true
        }

        try {
            cameraManager.openCamera(backId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    backDevice = device
                    backStatus = "نجح الفتح ✅"
                    backDone = true
                    maybeFinish()
                }
                override fun onDisconnected(device: CameraDevice) {
                    backStatus = "انقطع الاتصال بعد الفتح"
                    backDone = true
                    maybeFinish()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    backStatus = "فشل - كود الخطأ $error (${cameraErrorName(error)})"
                    backDone = true
                    maybeFinish()
                }
            }, handler)
        } catch (e: Exception) {
            backStatus = "استثناء: ${e.javaClass.simpleName} - ${e.message}"
            backDone = true
        }

        maybeFinish()
    }

    private fun cameraErrorName(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "الكاميرا مستخدمة من تطبيق آخر"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "تم الوصول للحد الأقصى من الكاميرات المفتوحة في نفس الوقت (هذا هو المؤشر الأهم لعدم دعم التزامن)"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "الكاميرا معطلة بواسطة سياسة الجهاز"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "خطأ في جهاز الكاميرا نفسه"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "خطأ في خدمة الكاميرا بالنظام"
        else -> "غير معروف"
    }
}
