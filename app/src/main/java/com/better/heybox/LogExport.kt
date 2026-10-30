package com.better.heybox

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExport {

    private const val TAG = "BetterHeybox"

    @JvmStatic
    fun buildExportText(context: Context?): String {
        val sb = StringBuilder(8192)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.append("========== BetterHeybox 模块日志导出 ==========\n")
        sb.append("导出时间: ").append(time).append('\n')
        sb.append("模块版本: ").append(VersionUtils.getVersionName(context)).append('\n')
        sb.append("构建类型: ").append(if (BuildFlags.DEBUG) "debug" else "release").append('\n')
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE)
            .append(", SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("导出进程 pid: ").append(Process.myPid()).append('\n')
        val moduleProcess = isModuleProcess()
        if (moduleProcess) {
            sb.append("框架服务: ").append(if (App.getService() == null) "未连接" else "已连接")
                .append('\n')
            val heyboxStatus = App.readString(App.KEY_RUNTIME_STATUS, "")
            if (!heyboxStatus.isNullOrEmpty()) {
                sb.append('\n').append("----- 小黑盒进程运行状态（跨进程检查点快照） -----\n")
                    .append(heyboxStatus).append('\n')
            }
        }

        sb.append('\n')
            .append(if (moduleProcess) "----- 模块进程检查点 -----\n" else "----- 本进程（小黑盒）检查点 -----\n")
            .append(Checkpoint.dump()).append('\n')

        sb.append('\n').append("----- 模块统计（本进程） -----\n")
            .append(ModuleStats.snapshot()).append('\n')

        LogRecorder.flush()
        appendFile(sb, LogRecorder.getLogFilePath(), "日志 log.txt")
        appendFile(sb, LogRecorder.getLogBackupFilePath(), "上一份日志 log.1.txt")
        return sb.toString()
    }

    private fun isModuleProcess(): Boolean {
        val name = App.currentProcessName()
        return name != null && name.startsWith("com.better.heybox")
    }

    private fun appendFile(sb: StringBuilder, path: String?, title: String) {
        if (path == null) {
            return
        }
        val file = File(path)
        if (!file.exists() || !file.isFile()) {
            return
        }
        try {
            val data = file.inputStream().use { readAll(it) }
            sb.append('\n').append("===== ").append(title).append(" =====\n")
                .append(String(data, StandardCharsets.UTF_8))
            if (data.isNotEmpty() && data[data.size - 1] != '\n'.code.toByte()) {
                sb.append('\n')
            }
        } catch (t: Throwable) {
            Log.e(TAG, "读取日志文件失败: $path", t)
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
