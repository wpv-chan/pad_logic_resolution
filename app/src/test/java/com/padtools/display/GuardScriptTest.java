package com.padtools.display;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Execute the generated shell against a mock wm, including detached timeout and confirmation races. */
public class GuardScriptTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private Path directory, base, script, commands;
    private DisplayState previous;

    @Before public void setUp() throws Exception {
        directory = Files.createTempDirectory("pad-display-test-");
        String selectedShell = System.getProperty("pad.test.shell", "sh");
        if (!selectedShell.equals("sh")) {
            Path shim = directory.resolve("sh");
            write(shim, "#!/bin/sh\nexec " + GuardScript.quote(selectedShell) + " \"$@\"\n");
            assertTrue(shim.toFile().setExecutable(true));
        }
        base = directory.resolve("pad-display-" + TOKEN);
        script = Path.of(base + ".sh"); commands = directory.resolve("commands");
        Path mock = directory.resolve("wm");
        write(mock, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$TEST_COMMANDS\"\n");
        assertTrue(mock.toFile().setExecutable(true));
        previous = new DisplayState(2560, 1600, 320, 1920, 1200, 240);
        write(script, local(GuardScript.watchdog(TOKEN, previous)));
        Files.createFile(Path.of(base + ".pending"));
    }

    private String local(String command) { return command.replace("/data/local/tmp", directory.toString()); }
    private Process start(String command) throws Exception {
        String testShell = System.getProperty("pad.test.shell", "sh");
        ProcessBuilder process = new ProcessBuilder(testShell, "-c", local(command));
        process.environment().put("PATH", directory + ":" + process.environment().get("PATH"));
        process.environment().put("TEST_COMMANDS", commands.toString());
        process.environment().put("PAD_TEST_SHELL", testShell);
        process.redirectErrorStream(true); return process.start();
    }
    private String execute(String command) throws Exception {
        Process process = start(command);
        assertTrue("Shell command timed out", process.waitFor(5, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.exitValue()); return output.trim();
    }
    private static void write(Path path, String value) throws Exception { Files.write(path, value.getBytes(StandardCharsets.UTF_8)); }
    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private String recorded() throws Exception { return Files.exists(commands) ? read(commands) : ""; }

    @Test public void revertsBothPreviousOverrides() throws Exception {
        assertEquals("restored", execute(GuardScript.finish(TOKEN, false)));
        assertEquals("size 1920x1200\ndensity 240\n", recorded());
        assertFalse(Files.exists(Path.of(base + ".pending")));
    }
    @Test public void restoresDefaultsIfPreviousStateHadNoOverrides() throws Exception {
        write(script, local(GuardScript.watchdog(TOKEN, new DisplayState(2560, 1600, 320, null, null, null))));
        execute(GuardScript.finish(TOKEN, false));
        assertEquals("size reset\ndensity reset\n", recorded());
    }
    @Test public void confirmedValuesAreNeverRevertedByWatchdog() throws Exception {
        assertEquals("kept", execute(GuardScript.finish(TOKEN, true)));
        write(script, read(script).replace("sleep 30;", "sleep 0.1;"));
        assertEquals("kept", execute("sh " + script + " watch"));
        assertEquals("", recorded());
    }
    @Test public void detachedWatchdogRevertsAfterLaunchingShellHasExited() throws Exception {
        write(script, read(script).replace("sleep 30;", "sleep 0.2;"));
        execute("nohup sh " + script + " watch >/dev/null 2>&1 </dev/null &");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (Files.exists(Path.of(base + ".pending")) && System.nanoTime() < until) Thread.sleep(20);
        assertFalse(Files.exists(Path.of(base + ".pending")));
        assertEquals("size 1920x1200\ndensity 240\n", recorded());
    }
    @Test public void concurrentKeepAndRevertHaveOneConsistentOutcome() throws Exception {
        Process keep = start(GuardScript.finish(TOKEN, true));
        Process revert = start(GuardScript.finish(TOKEN, false));
        assertTrue(keep.waitFor(5, TimeUnit.SECONDS)); assertTrue(revert.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, keep.exitValue()); assertEquals(0, revert.exitValue());
        String result = read(Path.of(base + ".result")).trim();
        assertTrue(result.equals("kept") || result.equals("restored"));
        assertEquals(result.equals("kept") ? "" : "size 1920x1200\ndensity 240\n", recorded());
    }
    @Test public void appliesOnlyWhileTransactionIsStillPending() throws Exception {
        execute(GuardScript.apply(TOKEN, new DisplaySpec(1280, 800, 160)));
        assertEquals("size 1280x800\ndensity 160\n", recorded());
        execute(GuardScript.finish(TOKEN, true));
        Process expired = start(GuardScript.apply(TOKEN, new DisplaySpec(2560, 1600, 320)));
        assertTrue(expired.waitFor(5, TimeUnit.SECONDS)); assertEquals(1, expired.exitValue());
        assertEquals("size 1280x800\ndensity 160\n", recorded());
    }
    @Test public void cleanupCannotRemoveActiveRollback() throws Exception {
        execute(GuardScript.cleanup(TOKEN)); assertTrue(Files.exists(script));
        execute(GuardScript.finish(TOKEN, false)); execute(GuardScript.cleanup(TOKEN));
        assertFalse(Files.exists(script));
    }

    @Test public void preparationMustFinishBeforeReturningToTheAdbCaller() throws Exception {
        // Delay launching to reproduce the original async preparation race deterministically.
        Path nohup = directory.resolve("nohup");
        write(nohup, "#!/bin/sh\nsleep 0.2\nexec /usr/bin/nohup \"$@\"\n");
        assertTrue(nohup.toFile().setExecutable(true));
        String command = GuardScript.arm(TOKEN, previous).replace("sleep 30;", "sleep 0.2;");
        assertEquals("armed", execute(command));
        assertTrue(Files.exists(Path.of(base + ".armed")));
        // Let the detached watchdog finish before deleting its files.
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Files.exists(Path.of(base + ".pending")) && System.nanoTime() < until) Thread.sleep(20);
    }

    @Test public void lockFailureMustHaveAUsefulDiagnostic() throws Exception {
        Files.createDirectory(Path.of(base + ".lock"));
        Process process = start(GuardScript.apply(TOKEN, new DisplaySpec(1280, 800, 160)));
        assertTrue(process.waitFor(3, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue());
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertFalse("A bare exit code cannot identify an apply failure", output.trim().isEmpty());
    }

    @Test public void startupFailureMustIncludeTheWatchdogError() throws Exception {
        Path nohup = directory.resolve("nohup");
        write(nohup, "#!/bin/sh\necho 'Permission denied: background launch rejected' >&2\nexit 1\n");
        assertTrue(nohup.toFile().setExecutable(true));
        Process process = start(GuardScript.arm(TOKEN, previous));
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(1, process.exitValue());
        assertTrue(output, output.contains("background launch rejected"));
        assertEquals("", recorded());
    }

    @Test public void writeFailureMustKeepSystemErrorAndIdentifyDensityCommand() throws Exception {
        Path mock = directory.resolve("wm");
        write(mock, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$TEST_COMMANDS\"\n"
                + "if [ \"$1\" = density ]; then echo 'SecurityException: WRITE_SECURE_SETTINGS' >&2; exit 1; fi\n");
        Process process = start(GuardScript.apply(TOKEN, new DisplaySpec(1280, 800, 160)));
        assertTrue(process.waitFor(3, TimeUnit.SECONDS)); assertEquals(1, process.exitValue());
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(output, output.contains("WRITE_SECURE_SETTINGS"));
        assertTrue(output, output.contains("APPLY_DENSITY_FAILED"));
        assertFalse(Files.exists(Path.of(base + ".lock")));
    }

    @Test public void unchangedTransactionCanBeCancelledWithoutWritingDisplaySettings() throws Exception {
        assertEquals("restored", execute(GuardScript.cancelUnchanged(TOKEN)));
        assertEquals("", recorded());
        assertFalse(Files.exists(Path.of(base + ".pending")));
        assertFalse(Files.exists(Path.of(base + ".lock")));
    }

    private String executeFramed(String command) throws Exception {
        String marker = "PAD_TEST_EXIT";
        Process process = start(ShellCommand.wrap(command, marker));
        String output = ShellCommand.readResult(process.getInputStream(), marker);
        process.getInputStream().close();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        return output;
    }

    @Test public void completeApplyAndKeepFlowWorksThroughAdbResultFrames() throws Exception {
        assertEquals("armed", executeFramed(GuardScript.arm(TOKEN, previous).replace("sleep 30;", "sleep 1;")));
        assertEquals("", executeFramed(GuardScript.apply(TOKEN, new DisplaySpec(1280, 800, 160))));
        assertEquals("kept", executeFramed(GuardScript.finish(TOKEN, true)));
        assertEquals("size 1280x800\ndensity 160\n", recorded());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Files.exists(Path.of(base + ".armed")) && System.nanoTime() < until) Thread.sleep(20);
        assertEquals("size 1280x800\ndensity 160\n", recorded());
    }

    @After public void cleanUp() throws Exception {
        if (directory != null) try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
