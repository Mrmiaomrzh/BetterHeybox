package com.better.heybox;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块日志导出：把「运行状态检查点 + 日志文件」打包成排查文本。
 * 小黑盒进程日志因沙箱不可读，但其检查点已写入 RemotePreferences，合并进同一份导出
 */
public final class LogExport {

    private static final String TAG = "BetterHeybox";

    private LogExport() {
    }

    public static String buildExportText(Context context) {
        StringBuilder sb = new StringBuilder(8192);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        sb.append("========== BetterHeybox 模块日志导出 ==========\n");
        sb.append("导出时间: ").append(time).append('\n');
        sb.append("模块版本: ").append(VersionUtils.getVersionName(context)).append('\n');
        sb.append("构建类型: ").append(BuildFlags.DEBUG ? "debug" : "release").append('\n');
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE)
                .append(", SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("导出进程 pid: ").append(android.os.Process.myPid()).append('\n');
        boolean moduleProcess = isModuleProcess();
        if (moduleProcess) {
            sb.append("框架服务: ").append(App.getService() == null ? "未连接" : "已连接").append('\n');
            String heyboxStatus = App.readString(App.KEY_RUNTIME_STATUS, "");
            if (heyboxStatus != null && !heyboxStatus.isEmpty()) {
                sb.append('\n').append("----- 小黑盒进程运行状态（跨进程检查点快照） -----\n")
                        .append(heyboxStatus).append('\n');
            }
        }

        sb.append('\n').append(moduleProcess ? "----- 模块进程检查点 -----\n"
                : "----- 本进程（小黑盒）检查点 -----\n")
                .append(Checkpoint.dump()).append('\n');

        appendFile(sb, LogRecorder.getLogFilePath(), "日志 log.txt");
        appendFile(sb, LogRecorder.getLogBackupFilePath(), "上一份日志 log.1.txt");
        return sb.toString();
    }

    /** 进程名是否以 com.better.heybox 开头 */
    private static boolean isModuleProcess() {
        String name = App.currentProcessName();
        return name != null && name.startsWith("com.better.heybox");
    }

    private static void appendFile(StringBuilder sb, String path, String title) {
        if (path == null) {
            return;
        }
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return;
        }
        try {
            byte[] data;
            try (InputStream in = new FileInputStream(file)) {
                data = readAll(in);
            }
            sb.append('\n').append("===== ").append(title).append(" =====\n")
                    .append(new String(data, StandardCharsets.UTF_8));
            if (data.length > 0 && data[data.length - 1] != '\n') {
                sb.append('\n');
            }
        } catch (Throwable t) {
            Log.e(TAG, "读取日志文件失败: " + path, t);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
