package com.dan.qrbatch;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

final class GalleryDecoder {
    static String read(ContentResolver resolver, Uri uri) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(input, null, options); }
        if (options.outWidth <= 0 || options.outHeight <= 0 || (long) options.outWidth * options.outHeight > 40000000L) {
            throw new IllegalArgumentException("图片无效或超过 4000 万像素，请裁剪二维码后重试。");
        }
        options.inJustDecodeBounds = false; options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1800) options.inSampleSize *= 2;
        Bitmap bitmap;
        try (InputStream input = resolver.openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(input, null, options); }
        if (bitmap == null) throw new IllegalArgumentException("无法读取图片，请使用清晰的 JPG 或 PNG。");
        try {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int[] pixels = new int[width * height]; bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            QRCodeReader reader = new QRCodeReader();
            try { return reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints).getText(); }
            catch (com.google.zxing.ReaderException error) {
                try { return reader.decode(new BinaryBitmap(new HybridBinarizer(source.invert())), hints).getText(); }
                catch (com.google.zxing.ReaderException ignored) { throw new IllegalArgumentException("没有识别到二维码，请裁剪并保留四周留白。"); }
            }
        } finally { bitmap.recycle(); }
    }
}
