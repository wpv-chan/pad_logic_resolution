package com.padtools.display;

/** A detached shell watchdog survives Activity recreation, app death and ADB disconnection. */
public final class GuardScript {
    public static final int TIMEOUT_SECONDS = 30;
    private GuardScript() {}
    public static String base(String token) {
        if (!token.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid transaction token");
        return "/data/local/tmp/pad-display-" + token;
    }
    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    public static String watchdog(String token, DisplayState previous) {
        String b = base(token);
        return "#!/system/bin/sh\n"
                + "base=" + quote(b) + "\n"
                + "mode=${1:-watch}\n"
                + "if [ \"$mode\" = watch ]; then trap '' HUP; touch \"$base.armed\" || { echo '[GUARD_ARM_FAILED] Cannot create acknowledgement'; exit 1; }; sleep " + TIMEOUT_SECONDS + "; fi\n"
                + "i=0; until mkdir \"$base.lock\" 2>/dev/null; do i=$((i+1)); if [ \"$i\" -gt 40 ]; then echo '[GUARD_LOCK_FAILED] Could not acquire display transaction lock'; ls -ld \"$base.lock\" 2>&1; exit 1; fi; sleep 0.1; done\n"
                + "trap 'pad_exit=$?; trap - EXIT; rmdir \"$base.lock\" 2>/dev/null || :; exit \"$pad_exit\"' EXIT\n"
                + "if [ -f \"$base.pending\" ]; then\n"
                + "  if [ \"$mode\" = keep ]; then\n"
                + "    rm -f \"$base.pending\"; echo kept > \"$base.result\"\n"
                + "  else\n"
                + "    wm size " + previous.restoreSize() + "; a=$?\n"
                + "    [ \"$a\" = 0 ] || echo \"[RESTORE_SIZE_FAILED] wm size exit=$a\"\n"
                + "    wm density " + previous.restoreDensity() + "; b=$?\n"
                + "    [ \"$b\" = 0 ] || echo \"[RESTORE_DENSITY_FAILED] wm density exit=$b\"\n"
                + "    if [ \"$a\" = 0 ] && [ \"$b\" = 0 ]; then rm -f \"$base.pending\"; echo restored > \"$base.result\"; else echo restore_failed > \"$base.result\"; cat \"$base.result\"; exit 1; fi\n"
                + "  fi\n"
                + "fi\n"
                + "if [ -f \"$base.result\" ]; then cat \"$base.result\"; fi\n"
                + "if [ \"$mode\" = watch ]; then rm -f \"$base.armed\"; fi\n"
                + "exit 0\n";
    }

    public static String arm(String token, DisplayState previous) {
        String b = base(token);
        // Prepare synchronously. Only the final watchdog process is backgrounded.
        // Returning before acknowledgement lets the ADB caller close the setup session too early.
        return "umask 077; printf '%s\\n' " + quote(watchdog(token, previous)) + " > " + b + ".sh"
                + " || { echo '[GUARD_PREPARE_FAILED] Cannot write recovery script'; exit 1; }; "
                + "touch " + b + ".pending || { echo '[GUARD_PREPARE_FAILED] Cannot save pending marker'; exit 1; }; "
                + "rm -f " + b + ".armed; "
                + "nohup sh " + b + ".sh watch >" + b + ".log 2>&1 </dev/null &\n"
                + "pad_guard_pid=$!; printf '%s\\n' \"$pad_guard_pid\" > " + b + ".pid; "
                + waitForArm(token);
    }

    public static String waitForArm(String token) {
        String b = base(token);
        return "i=0; while [ ! -f " + b + ".armed ]; do "
                + "if [ -n \"${pad_guard_pid:-}\" ] && ! kill -0 \"$pad_guard_pid\" 2>/dev/null; then "
                + "echo '[GUARD_START_FAILED] Recovery process exited before acknowledgement'; cat " + b + ".log 2>/dev/null; exit 1; fi; "
                + "i=$((i+1)); if [ \"$i\" -gt 40 ]; then "
                + "echo '[GUARD_START_TIMEOUT] Recovery process did not acknowledge startup'; cat " + b + ".log 2>/dev/null; "
                + "ls -ld " + b + ".sh " + b + ".pending 2>&1; exit 1; fi; sleep 0.1; done; echo armed";
    }

    public static String apply(String token, DisplaySpec spec) {
        String b = base(token);
        return "mkdir " + b + ".lock 2>/dev/null || { echo '[APPLY_LOCK_FAILED] Display transaction lock is unavailable'; ls -ld " + b + ".lock 2>&1; exit 1; }; "
                + "trap " + quote("pad_exit=$?; trap - EXIT; rmdir " + b + ".lock 2>/dev/null || :; exit \"$pad_exit\"") + " EXIT; "
                + "trap 'exit 1' HUP INT TERM; "
                + "[ -f " + b + ".pending ] || { echo '[APPLY_EXPIRED] Display transaction is no longer pending'; exit 1; }; "
                + "wm size " + spec.sizeArg() + "; pad_code=$?; "
                + "if [ \"$pad_code\" != 0 ]; then echo \"[APPLY_SIZE_FAILED] wm size " + spec.sizeArg() + " exit=$pad_code\"; exit \"$pad_code\"; fi; "
                + "wm density " + spec.densityArg() + "; pad_code=$?; "
                + "if [ \"$pad_code\" != 0 ]; then echo \"[APPLY_DENSITY_FAILED] wm density " + spec.densityArg() + " exit=$pad_code\"; exit \"$pad_code\"; fi; exit 0";
    }

    /** The controller may call this only after verifying the display already matches its saved state. */
    public static String cancelUnchanged(String token) {
        String b = base(token);
        return "i=0; until mkdir " + b + ".lock 2>/dev/null; do i=$((i+1)); "
                + "if [ \"$i\" -gt 40 ]; then echo '[CANCEL_LOCK_FAILED] Cannot cancel the unchanged transaction'; exit 1; fi; sleep 0.1; done; "
                + "trap " + quote("pad_exit=$?; trap - EXIT; rmdir " + b + ".lock 2>/dev/null || :; exit \"$pad_exit\"") + " EXIT; "
                + "rm -f " + b + ".pending && printf 'restored\\n' > " + b + ".result "
                + "|| { echo '[CANCEL_WRITE_FAILED] Cannot cancel pending marker'; exit 1; }; echo restored";
    }

    public static String finish(String token, boolean keep) {
        return "sh " + base(token) + ".sh " + (keep ? "keep" : "revert");
    }

    public static String status(String token) {
        String b = base(token);
        return "if [ -f " + b + ".pending ]; then echo pending; fi; cat " + b + ".result 2>/dev/null; true";
    }

    public static String cleanup(String token) {
        String b = base(token);
        // Only finished transactions can be collected; active rollback files are never removed.
        return "if [ ! -f " + b + ".pending ] && [ ! -d " + b + ".lock ]; then rm -f "
                + b + ".sh " + b + ".result " + b + ".log " + b + ".pid; fi";
    }
}
