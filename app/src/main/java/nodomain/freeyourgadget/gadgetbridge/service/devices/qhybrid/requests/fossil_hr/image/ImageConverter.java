package nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil_hr.image;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.encoder.RLEEncoder;

public class ImageConverter {
    public static void encodeToTwoBitImage(byte monochromeImage){

    }

    public static byte[] encodeToRLEImage(byte[] monochromeImage, int height, int width) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(monochromeImage.length * 2);

        bos.write((byte) height);
        bos.write((byte) width);

        bos.write(RLEEncoder.RLEEncode(monochromeImage));

        bos.write((byte) 0x0FF);
        bos.write((byte) 0x0FF);

        return bos.toByteArray();
    }

    public static byte[] encodeToRawImage(byte[] monochromeImage, int height, int width){
        int pixelCount = height * width;

        byte[] result = new byte[pixelCount / 4]; // 4 pixels per byte e.g. 2 bits per pixel

        for(int i = 0; i < pixelCount; i++){
            int resultPixelIndex = i / 4;
            int shiftIndex = i % 4 * 2;

            result[resultPixelIndex] = (byte) ((monochromeImage[i] >> 6) << shiftIndex);
        }

        return result;
    }
}
