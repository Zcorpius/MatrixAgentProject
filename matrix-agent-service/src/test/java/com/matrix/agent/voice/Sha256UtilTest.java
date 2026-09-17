package com.matrix.agent.voice;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** {@link Sha256Util} 已知向量校验。 */
public final class Sha256UtilTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void knownContent_knownHex() throws Exception {
        File f = tmp.newFile();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("abc".getBytes("UTF-8"));
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Sha256Util.sha256Hex(f));
    }

    @Test
    public void emptyFile_knownHex() throws Exception {
        File f = tmp.newFile(); // 空
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Sha256Util.sha256Hex(f));
    }
}
