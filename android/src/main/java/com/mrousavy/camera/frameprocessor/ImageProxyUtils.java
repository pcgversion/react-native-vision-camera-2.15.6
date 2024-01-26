package com.mrousavy.camera.frameprocessor;

import android.annotation.SuppressLint;
import android.media.Image;

import androidx.annotation.Keep;
import androidx.camera.core.ImageProxy;
import com.facebook.proguard.annotations.DoNotStrip;

import java.nio.ByteBuffer;
@SuppressWarnings("unused") // used through JNI
@DoNotStrip
@Keep
public class ImageProxyUtils {
    @SuppressLint("UnsafeOptInUsageError")
    @DoNotStrip
    @Keep
    public static boolean isImageProxyValid(ImageProxy imageProxy) {
        try {
            Image image = imageProxy.getImage();
            if (image == null) return false;
            // will throw an exception if the image is already closed
            imageProxy.getImage().getCropRect();
            // no exception thrown, image must still be valid.
            return true;
        } catch (Exception e) {
            // exception thrown, image has already been closed.
            return false;
        }
    }

    @DoNotStrip
    @Keep
    public static int getPlanesCount(ImageProxy imageProxy) {
        return imageProxy.getPlanes().length;
    }

    @DoNotStrip
    @Keep
    public static int getBytesPerRow(ImageProxy imageProxy) {
        return imageProxy.getPlanes()[0].getRowStride();
    }
    @DoNotStrip
    @Keep
    public static int getLumaValue(ImageProxy imageProxy) {
        java.nio.ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
        byte[] data = toByteArray(buffer);
        int total = 0;
        for (byte datum : data) {
            total += (int) datum & 0xFF;
        }
        int lumaValue = total / data.length;
        return lumaValue;
    }
    @DoNotStrip
    @Keep
    public static byte[] toByteArray(ByteBuffer byteBuffer) {
        byteBuffer.rewind(); // Rewind the buffer to zero
        byte[] data = new byte[byteBuffer.remaining()];
        byteBuffer.get(data); // Copy the buffer into a byte array
        return data; // Return the byte array
    }
}
