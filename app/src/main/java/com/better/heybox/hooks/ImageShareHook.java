package com.better.heybox.hooks;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import com.better.heybox.LogRecorder;

/**
 * 图片系统分享：图片查看流程的分享面板追加「系统分享」，下载图片后唤起系统分享界面,仅图片查看器生效
 */
public final class ImageShareHook {

    private final MainModule module;

    public ImageShareHook(MainModule module) {
        this.module = module;
    }
    public void install(ClassLoader cl) {
        hookImageLongPressMenu(cl);
    }

    private volatile Object pendingImageShareMediaData;
    private void hookImageLongPressMenu(ClassLoader cl) {
        try {
            Class<?> customizer = Class.forName(
                    "com.max.xiaoheihe.utils.imageviewer.ui.BaseResUICustomizer", false, cl);
            Class<?> mediaData = Class.forName(
                    "com.max.xiaoheihe.utils.imageviewer.MediaData", false, cl);
            Method getLocalHandlers = customizer.getDeclaredMethod("r", customizer, mediaData);
            module.hook(getLocalHandlers).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof List)) {
                    module.logd(Log.WARN, module.TAG, "图片长按处理器返回值不是 List: "
                            + (result == null ? "null" : result.getClass().getName()));
                    return result;
                }
                Object currentMediaData = chain.getArg(1);
                appendSystemShareHandler((List<?>) result, currentMediaData, cl);
                return result;
            });

            Method openShare = customizer.getDeclaredMethod("h0", mediaData);
            module.hook(openShare).intercept(chain -> {
                pendingImageShareMediaData = chain.getArg(0);
                module.logd(Log.INFO, module.TAG, "图片长按分享入口命中: mediaData="
                        + (pendingImageShareMediaData == null ? "null"
                        : pendingImageShareMediaData.getClass().getName()));
                return chain.proceed();
            });

            Class<?> dialogBuilder = Class.forName(
                    "com.max.xiaoheihe.accelworld.HBShareDialog$a", false, cl);
            Method addHandlers = dialogBuilder.getDeclaredMethod("c", List.class);
            module.hook(addHandlers).intercept(chain -> {
                Object pending = pendingImageShareMediaData;
                if (pending == null) {
                    return chain.proceed();
                }
                pendingImageShareMediaData = null;
                Object handlers = chain.getArg(0);
                if (handlers instanceof List) {
                    module.logd(Log.INFO, module.TAG, "HBShareDialog 处理器列表命中（图片会话）: count="
                            + ((List<?>) handlers).size());
                    appendSystemShareHandler((List<?>) handlers, pending, cl);
                }
                return chain.proceed();
            });

            Class<?> shareDialog = Class.forName(
                    "com.max.xiaoheihe.accelworld.HBShareDialog", false, cl);
            Method showDialog = shareDialog.getDeclaredMethod("g");
            module.hook(showDialog).intercept(chain -> {
                Object dialog = chain.getThisObject();
                if (!isImageForward(readForwardModel(dialog, cl), cl)) {
                    return chain.proceed();
                }
                Object actions = readShareDialogActions(dialog);
                if (actions instanceof List) {
                    appendSystemShareAction((List<?>) actions, cl, findDialogContext(dialog));
                }
                return chain.proceed();
            });

            Class<?> shareViewManager = Class.forName(
                    "com.max.common.common.share.ShareViewManager", false, cl);
            Class<?> forwardModel = Class.forName(
                    "com.max.data.model.share.IForwardModel", false, cl);
            Method buildForwardActions = shareViewManager.getDeclaredMethod(
                    "m", Context.class, forwardModel, List.class);
            module.hook(buildForwardActions).intercept(chain -> {
                Object result = chain.proceed();
                Object actions = chain.getArg(2);
                if (actions instanceof List && isImageForward(chain.getArg(1), cl)) {
                    Object ctxArg = chain.getArg(0);
                    appendSystemShareAction((List<?>) actions, cl,
                            ctxArg instanceof Context ? (Context) ctxArg : null);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 图片长按真实分享面板 Hook 已安装: BaseResUICustomizer.r");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 图片长按真实分享面板 Hook 失败", t);
        }
    }

    private void appendSystemShareAction(List<?> actions, ClassLoader cl, Context context) {
        try {
            if (!module.isEnabled(App.KEY_SYSTEM_SHARE, true)) {
                return;
            }
            for (Object actionObject : actions) {
                if (actionObject == null) {
                    continue;
                }
                Method getAction = actionObject.getClass().getMethod("getAction");
                Object action = getAction.invoke(actionObject);
                Method getActionTag = action.getClass().getMethod("getActionTag");
                if ("SystemShare".equals(String.valueOf(getActionTag.invoke(action)))) {
                    return;
                }
            }

            Class<?> actionObjClass = Class.forName(
                    "com.max.data.bean.share.ActionObj", false, cl);
            Class<?> actionClass = Class.forName(
                    "com.max.data.model.share.IAction", false, cl);
            Class<?> customActionClass = Class.forName(
                    "com.max.data.model.share.IAction$CustomAction", false, cl);
            Object customAction = customActionClass.getConstructor(String.class)
                    .newInstance("SystemShare");

            Object actionObject = actionObjClass.getConstructor(
                            String.class, Integer.class, String.class, String.class,
                            String.class, String.class, actionClass)
                    .newInstance("系统分享", resolveShareArrowIcon(context), null, null, null, null, customAction);
            @SuppressWarnings("unchecked")
            List<Object> mutableActions = (List<Object>) actions;
            mutableActions.add(actionObject);
            module.logd(Log.INFO, module.TAG, "图片长按菜单动作已追加系统分享: count="
                    + mutableActions.size());
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "追加图片系统分享动作失败", t);
        }
    }

    private void appendSystemShareHandler(List<?> handlers, Object mediaData, ClassLoader cl) {
        try {
            if (!module.isEnabled(App.KEY_SYSTEM_SHARE, true)) {
                return;
            }
            for (Object handler : handlers) {
                if (handler == null) {
                    continue;
                }
                Method getTarget = handler.getClass().getMethod("getTarget");
                if ("SystemShare".equals(String.valueOf(getTarget.invoke(handler)))) {
                    return;
                }
            }

            Class<?> localHandler = Class.forName(
                    "com.max.common.common.share.local.c", false, cl);
            Class<?> callbackType = findLocalHandlerCallback(cl);
            if (callbackType == null) {
                module.logd(Log.WARN, module.TAG, "未找到本地分享回调接口，跳过系统分享处理器");
                return;
            }
            InvocationHandler callback = (proxy, method, args) -> {
                if ("invoke".equals(method.getName())) {
                    module.logd(Log.INFO, module.TAG, "图片系统分享处理器已命中");
                    shareImageWithSystemChooser(mediaData);
                }
                if (method.getReturnType() == Void.TYPE) {
                    return null;
                }
                return readKotlinUnit(cl);
            };
            Object callbackProxy = Proxy.newProxyInstance(
                    cl, new Class<?>[]{callbackType}, callback);
            Object action = localHandler.getConstructor(String.class, callbackType)
                    .newInstance("SystemShare", callbackProxy);
            @SuppressWarnings("unchecked")
            List<Object> mutableHandlers = (List<Object>) handlers;
            mutableHandlers.add(action);
            module.logd(Log.INFO, module.TAG, "图片长按处理器已追加系统分享: count=" + mutableHandlers.size());
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "追加图片系统分享处理器失败", t);
        }
    }

    private Object readKotlinUnit(ClassLoader cl) {
        try {
            Class<?> unit = Class.forName("kotlin.b2", false, cl);
            Field instance = null;
            try {
                instance = unit.getDeclaredField("f140421a");
            } catch (NoSuchFieldException ignored) {
                instance = unit.getDeclaredField("f140881a");
            }
            instance.setAccessible(true);
            return instance.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private Object readShareDialogActions(Object dialog) {
        if (dialog == null) {
            return null;
        }
        Object actions = readField(dialog, "f83135h");
        if (actions == null) {
            actions = readField(dialog, "f83116h");
        }
        return actions;
    }

    private Class<?> findLocalHandlerCallback(ClassLoader cl) {
        try {
            Class<?> localHandler = Class.forName(
                    "com.max.common.common.share.local.c", false, cl);
            for (Constructor<?> ctor : localHandler.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 2 && params[0] == String.class && params[1].isInterface()) {
                    return params[1];
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "解析本地分享回调接口失败", t);
        }
        return null;
    }

    /**
 * 分享动作图标：按名称运行时解析
 */
    private static int resolveShareArrowIcon(Context context) {
        if (context == null) {
            return 0;
        }
        try {
            int id = context.getResources().getIdentifier(
                    "bbs_sharebutton_forward_46x46", "drawable", MainModule.TARGET_PKG);
            return id;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 从 HBShareDialog 对象反射取 Context 字段 */
    private Context findDialogContext(Object dialog) {
        if (dialog == null) {
            return null;
        }
        try {
            for (Field f : dialog.getClass().getDeclaredFields()) {
                if (f.getType() == Context.class) {
                    f.setAccessible(true);
                    Object v = f.get(dialog);
                    return v instanceof Context ? (Context) v : null;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 面板门禁：ImageForwardModel 全 app 仅图片查看器构造，命中即视为图片分享面板 */
    private static boolean isImageForward(Object forward, ClassLoader cl) {
        if (forward == null) {
            return false;
        }
        try {
            return Class.forName("com.max.data.model.common.ImageForwardModel", false, cl)
                    .isInstance(forward);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按类型取 dialog 的 forward 字段*/
    private static Object readForwardModel(Object dialog, ClassLoader cl) {
        if (dialog == null) {
            return null;
        }
        try {
            Class<?> forwardType = Class.forName(
                    "com.max.data.model.share.IForwardModel", false, cl);
            for (Field f : dialog.getClass().getDeclaredFields()) {
                if (f.getType() == forwardType) {
                    f.setAccessible(true);
                    return f.get(dialog);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean shareImageWithSystemChooser(Object mediaData) {
        if (mediaData == null) {
            return false;
        }
        try {
            Method urlMethod = mediaData.getClass().getMethod("U");
            Method contextMethod = mediaData.getClass().getMethod("n");
            String imageUrl = String.valueOf(urlMethod.invoke(mediaData));
            Object contextObject = contextMethod.invoke(mediaData);
            if (!(contextObject instanceof Context) || imageUrl.length() == 0
                    || "null".equals(imageUrl)) {
                module.logd(Log.WARN, module.TAG, "图片分享跳过: MediaData 缺少 URL 或 Context");
                return false;
            }
            Context context = (Context) contextObject;
            LogRecorder.setContext(context);
            module.logd(Log.INFO, module.TAG, "图片分享开始下载: url=" + imageUrl);
            new Thread(() -> shareDownloadedImage(context, imageUrl),
                    "BetterHeybox-image-share").start();
            return true;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "图片分享接管失败，回退原分享", t);
            return false;
        }
    }

    private void shareDownloadedImage(Context context, String imageUrl) {
        File output = null;
        try {
            output = downloadImage(context, imageUrl);
            String mime = guessMimeType(output.getName());
            // 优先写系统相册，失败回退 FileProvider
            Uri uri = publishToGallery(context, output);
            if (uri != null) {
                output.delete(); // 已复制进相册，临时文件不再需要
                module.logd(Log.INFO, module.TAG, "图片已保存到系统相册: uri=" + uri);
            } else {
                uri = getTargetFileUri(context, output);
            }
            if (uri == null) {
                throw new IllegalStateException("无法生成图片分享 URI");
            }
            final Uri shareUri = uri;
            final File finalOutput = output;
            context.getMainExecutor().execute(() -> {
                try {
                    Intent share = new Intent(Intent.ACTION_SEND)
                            .setType(mime)
                            .putExtra(Intent.EXTRA_STREAM, shareUri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent chooser = Intent.createChooser(share, "分享图片");
                    if (!(context instanceof Activity)) {
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                    context.startActivity(chooser);
                    module.logd(Log.INFO, module.TAG, "图片分享 chooser 已唤起: uri=" + shareUri + " mime=" + mime);
                } catch (Throwable t) {
                    module.logd(Log.ERROR, module.TAG, "图片分享 chooser 启动失败", t);
                    if (finalOutput != null) {
                        finalOutput.delete();
                    }
                }
            });
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "图片分享准备失败，回退原分享", t);
            if (output != null) {
                output.delete();
            }
            context.getMainExecutor().execute(() ->
                    Toast.makeText(context, "图片暂时无法分享", Toast.LENGTH_SHORT).show());
        }
    }

    private File downloadImage(Context context, String imageUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 heybox");
        connection.setRequestProperty("Referer", "https://api.xiaoheihe.cn/");
        connection.connect();
        if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
            throw new IllegalStateException("HTTP " + connection.getResponseCode());
        }
        // 写到外部缓存目录：wbsdk_filepaths 的 external-cache-path 覆盖它，FileProvider 回退路径可用
        File dir = context.getExternalCacheDir();
        if (dir == null) {
            dir = context.getCacheDir();
        }
        File shareDir = new File(dir, "betterheybox-share");
        if (!shareDir.exists() && !shareDir.mkdirs()) {
            throw new IllegalStateException("无法创建分享缓存目录");
        }
        File tmp = new File(shareDir, "image-" + System.currentTimeMillis() + ".tmp");
        try (InputStream input = connection.getInputStream();
             FileOutputStream file = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                file.write(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }
        // 按文件头识别真实格式，避免扩展名与内容不符导致接收方无法查看/解码
        String ext = sniffImageExtension(tmp);
        if (ext == null) {
            ext = guessExtensionFromUrl(imageUrl);
        }
        File output = new File(shareDir, "image-" + System.currentTimeMillis() + "." + ext);
        if (!tmp.renameTo(output)) {
            output = tmp; // 重命名失败则沿用 tmp
        }
        return output;
    }

    /** 写入系统相册，返回 content URI；失败返回 null */
    private Uri publishToGallery(Context context, File file) {
        try {
            if (Build.VERSION.SDK_INT < 29) {
                // Android 8~9 写入公共相册需要存储权限
                if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    module.logd(Log.WARN, module.TAG, "无 WRITE_EXTERNAL_STORAGE 权限，回退 FileProvider");
                    return null;
                }
            }
            String mime = guessMimeType(file.getName());
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, file.getName());
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/BetterHeybox");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = context.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                 InputStream in = new FileInputStream(file)) {
                if (os == null) {
                    return null;
                }
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    os.write(buffer, 0, count);
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues published = new ContentValues();
                published.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, published, null, null);
            }
            return uri;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "写入系统相册失败，回退 FileProvider: " + t);
            return null;
        }
    }

    private static String sniffImageExtension(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] head = new byte[12];
            int read = in.read(head);
            if (read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
                return "jpg";
            }
            if (read >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47) {
                return "png";
            }
            if (read >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') {
                return "gif";
            }
            if (read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                return "webp";
            }
            if (read >= 2 && head[0] == 'B' && head[1] == 'M') {
                return "bmp";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 从 URL 路径猜测图片扩展名 */
    private static String guessExtensionFromUrl(String imageUrl) {
        try {
            String path = new URL(imageUrl).getPath();
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot + 1).toLowerCase();
                if (ext.matches("(jpg|jpeg|png|gif|webp|bmp|heic|heif)")) {
                    return ext.equals("jpeg") ? "jpg" : ext;
                }
            }
        } catch (Throwable ignored) {
        }
        return "jpg";
    }

    private static String guessMimeType(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".gif")) {
            return "image/gif";
        }
        if (n.endsWith(".webp")) {
            return "image/webp";
        }
        if (n.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (n.endsWith(".heic") || n.endsWith(".heif")) {
            return "image/heic";
        }
        return "image/jpeg";
    }
    private Uri getTargetFileUri(Context context, File file) throws Exception {
        Class<?> provider = Class.forName("androidx.core.content.FileProvider",
                true, context.getClassLoader());
        String authority = MainModule.TARGET_PKG + ".fileprovider";
        String[] methodNames = {"getUriForFile", "h", "i"};
        for (String methodName : methodNames) {
            try {
                Method method = provider.getDeclaredMethod(
                        methodName, Context.class, String.class, File.class);
                method.setAccessible(true);
                return (Uri) method.invoke(null, context, authority, file);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("FileProvider URI method not found");
    }
}
