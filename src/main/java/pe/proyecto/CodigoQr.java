package pe.proyecto;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;

final class CodigoQr {
    private CodigoQr() {}

    static String imagen(String codigo) {
        try {
            int lado = 240;
            var matriz = new QRCodeWriter().encode(codigo, BarcodeFormat.QR_CODE, lado, lado,
                    Map.of(EncodeHintType.MARGIN, 4));
            var imagen = new BufferedImage(lado, lado, BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < lado; y++) {
                for (int x = 0; x < lado; x++) {
                    imagen.setRGB(x, y, matriz.get(x, y) ? 0xff000000 : 0xffffffff);
                }
            }
            var salida = new ByteArrayOutputStream();
            ImageIO.write(imagen, "png", salida);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(salida.toByteArray());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } catch (WriterException error) {
            throw new IllegalStateException("No se pudo generar la imagen de recojo.", error);
        }
    }
}

