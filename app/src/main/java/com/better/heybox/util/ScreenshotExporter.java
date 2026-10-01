package com.better.heybox.util;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;

import com.better.heybox.App;
import com.better.heybox.HeyboxPrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public final class ScreenshotExporter {

    private static final String FOLDER_NAME = "Heybox";
    private static final String FILE_PREFIX = "heybox_screenshot_";
    private static final String MIME_TYPE = "image/png";

    private ScreenshotExporter() {
    }

    public static Uri saveScreenshot(Bitmap screenshot, Context context) {
        if (screenshot == null || context == null || screenshot.isRecycled()) {
            return null;
        }

        Bitmap finalBitmap = screenshot;
        boolean watermarked = false;
        try {
            if (HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_ADD_WATERMARK, false)) {
                finalBitmap = addWatermark(screenshot, context);
                watermarked = finalBitmap != screenshot;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return saveToMediaStore(finalBitmap, context);
            }
            return saveToLegacyStorage(finalBitmap, context);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (watermarked && finalBitmap != null && !finalBitmap.isRecycled()) {
                finalBitmap.recycle();
            }
        }
    }

    public static void shareScreenshot(Context context, Uri uri) {
        if (context == null || uri == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(MIME_TYPE);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(intent, "分享截图");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(chooser);
        } catch (Throwable ignored) {

        }
    }

    private static Uri saveToMediaStore(Bitmap bitmap, Context context) throws Exception {
        String filename = FILE_PREFIX + System.currentTimeMillis() + ".png";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE);
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + FOLDER_NAME);

        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = context.getContentResolver().insert(collection, values);
        if (itemUri == null) {
            return null;
        }

        try (OutputStream out = context.getContentResolver().openOutputStream(itemUri)) {
            if (out == null) {
                return null;
            }
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return null;
            }
        }
        return itemUri;
    }

    private static Uri saveToLegacyStorage(Bitmap bitmap, Context context) throws Exception {
        File picturesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES);
        File heyboxDir = new File(picturesDir, FOLDER_NAME);
        if (!heyboxDir.exists() && !heyboxDir.mkdirs()) {
            return null;
        }

        String filename = FILE_PREFIX + System.currentTimeMillis() + ".png";
        File outputFile = new File(heyboxDir, filename);

        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return null;
            }
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE);
        values.put(MediaStore.Images.Media.DATA, outputFile.getAbsolutePath());
        try {
            Uri registered = context.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (registered != null) {
                return registered;
            }
        } catch (Throwable ignored) {

        }
        return Uri.fromFile(outputFile);
    }

    private static Bitmap addWatermark(Bitmap source, Context context) {
        try {
            Bitmap.Config config = source.getConfig() != null
                    ? source.getConfig()
                    : Bitmap.Config.ARGB_8888;
            Bitmap watermarked = Bitmap.createBitmap(source.getWidth(), source.getHeight(), config);
            Canvas canvas = new Canvas(watermarked);
            canvas.drawBitmap(source, 0, 0, null);

            float density = context.getResources().getDisplayMetrics().density;
            float textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 11, context.getResources().getDisplayMetrics());
            if (textSize <= 0) {
                textSize = 11 * density;
            }

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.argb(110, 0, 0, 0));
            paint.setTextSize(textSize);
            paint.setShadowLayer(Math.max(1f, density), 0f, 0f, Color.argb(60, 255, 255, 255));

            String watermark = "BetterHeybox";
            float textWidth = paint.measureText(watermark);
            float margin = 8 * density;
            float x = Math.max(margin, canvas.getWidth() - textWidth - margin);
            float y = canvas.getHeight() - margin;

            canvas.drawText(watermark, x, y, paint);
            return watermarked;
        } catch (Throwable ignored) {
            return source;
        }
    }
}
