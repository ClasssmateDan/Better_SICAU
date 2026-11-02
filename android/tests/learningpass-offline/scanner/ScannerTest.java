import com.dan.qrbatch.core.QrPayload;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** Synthetic codec integration only. Does not run a camera or send network requests. */
public final class ScannerTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static String decode(LuminanceSource source) throws Exception {
        Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source)), hints).getText();
    }
    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]);
        BufferedImage png = ImageIO.read(fixture.toFile());
        String expected = Files.readString(Path.of(args[1]));
        String read = decode(new RGBLuminanceSource(png.getWidth(), png.getHeight(), png.getRGB(0,0,png.getWidth(),png.getHeight(),null,0,png.getWidth())));
        check(expected.equals(read), "PNG must decode to the complete original payload");
        check(read.substring(read.indexOf('?') + 1).split("&", -1).length == 7, "All seven synthetic parameters survive decoding");
        try { QrPayload.parse(read); throw new AssertionError("non-Chaoxing host must be rejected"); }
        catch (IllegalArgumentException expectedRejection) { assertions++; }
        QrPayload query = QrPayload.parse(read.substring(read.indexOf('?') + 1));
        check(query.activeId.equals("5000000000000"), "activity id must be preserved");
        check(query.enc.equals("0123456789abcdef0123456789abcdef"), "enc must be preserved");

        String synthetic = "https://mobilelearn.chaoxing.com/widget/sign/e?id=5000000000000&c=001234&enc=synthetic_only";
        BitMatrix matrix = new QRCodeWriter().encode(synthetic, BarcodeFormat.QR_CODE, 480, 480);
        int width = matrix.getWidth(), height = matrix.getHeight();
        byte[] y = new byte[width * height];
        for (int row=0; row<height; row++) for (int col=0; col<width; col++) y[row*width+col]=(byte)(matrix.get(col,row)?0:255);
        for (int turn=0;turn<4;turn++) {
            String decoded = decode(new PlanarYUVLuminanceSource(y,width,height,0,0,width,height,false));
            check(decoded.equals(synthetic), "Y-plane must decode at rotation " + turn);
            QrPayload parsed = QrPayload.parse(decoded);
            check(parsed.code.equals("001234"), "code leading zero must survive");
            check(parsed.activeId.equals("5000000000000"), "synthetic activity must survive");
            byte[] rotated = new byte[y.length];
            for (int row=0;row<height;row++) for(int col=0;col<width;col++) rotated[col*height+(height-row-1)]=y[row*width+col];
            y=rotated; int oldWidth=width; width=height; height=oldWidth;
        }
        System.out.println("ScannerTest: " + assertions + " offline codec assertions passed (no camera/device/network)");
    }
}
