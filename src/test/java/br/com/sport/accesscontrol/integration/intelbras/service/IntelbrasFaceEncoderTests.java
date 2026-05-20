package br.com.sport.accesscontrol.integration.intelbras.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class IntelbrasFaceEncoderTests {

    @TempDir
    Path tempDir;

    @Test
    void convertsLocalImageToJpegBase64() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 2, 2);
        graphics.dispose();
        var path = tempDir.resolve("face.png");
        ImageIO.write(image, "png", path.toFile());

        var encoded = new IntelbrasFaceEncoder(tempDir.toString()).toJpegBase64(path.toString());
        var decoded = Base64.getDecoder().decode(encoded);

        assertThat(ImageIO.read(new ByteArrayInputStream(decoded))).isNotNull();
        assertThat(decoded[0]).isEqualTo((byte) 0xFF);
        assertThat(decoded[1]).isEqualTo((byte) 0xD8);
    }
}
