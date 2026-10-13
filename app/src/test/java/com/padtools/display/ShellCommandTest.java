package com.padtools.display;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ShellCommandTest {
    private static final String MARKER = "PAD_EXIT_test";
    private byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test public void commandCompletesWithoutWaitingForAdbEof() throws Exception {
        InputStream neverReadEof = new ByteArrayInputStream(bytes("armed\n" + MARKER + ":0\n")) {
            @Override public synchronized int read(byte[] b, int offset, int length) {
                if (available() == 0) throw new AssertionError("Command result is complete; waiting for EOF would freeze the UI");
                return super.read(b, offset, length);
            }
        };
        assertEquals("armed", ShellCommand.readResult(neverReadEof, MARKER));
    }

    @Test public void resultFrameCanSpanArbitraryAdbPackets() throws Exception {
        InputStream split = new ByteArrayInputStream(bytes("已执行\n" + MARKER + ":0\n")) {
            @Override public synchronized int read(byte[] b, int offset, int length) {
                return super.read(b, offset, Math.min(1, length));
            }
        };
        assertEquals("已执行", ShellCommand.readResult(split, MARKER));
    }

    @Test public void returnsUsefulSystemPermissionErrors() throws Exception {
        try {
            ShellCommand.readResult(new ByteArrayInputStream(bytes("SecurityException: WRITE_SECURE_SETTINGS\n" + MARKER + ":1\n")), MARKER);
            fail("Failed command must be surfaced");
        } catch (IOException e) { assertTrue(e.getMessage().contains("WRITE_SECURE_SETTINGS")); }
    }

    @Test(expected = IOException.class) public void connectionLossIsNotReportedAsSuccess() throws Exception {
        ShellCommand.readResult(new ByteArrayInputStream(bytes("armed\n")), MARKER);
    }

    @Test public void realShellWrapperPreservesSuccessfulOutput() throws Exception {
        Process process = new ProcessBuilder(System.getProperty("pad.test.shell", "sh"), "-c", ShellCommand.wrap("printf 'Physical size: 2560x1600\\n'", MARKER)).start();
        assertEquals("Physical size: 2560x1600", ShellCommand.readResult(process.getInputStream(), MARKER));
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
    }

    @Test public void realShellWrapperPreservesFailureExitCode() throws Exception {
        Process process = new ProcessBuilder(System.getProperty("pad.test.shell", "sh"), "-c", ShellCommand.wrap("printf 'denied\\n'; exit 7", MARKER)).start();
        try { ShellCommand.readResult(process.getInputStream(), MARKER); fail("Expected command failure"); }
        catch (IOException e) { assertTrue(e.getMessage().contains("7")); assertTrue(e.getMessage().contains("denied")); }
        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
    }
}
