package com.padtools.display;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Complete a command on its explicit result frame, rather than waiting for ADB stream EOF. */
final class ShellCommand {
    private ShellCommand() { }

    static String wrap(String command, String marker) {
        return "( " + command + " ) 2>&1; c=$?; printf '\\n" + marker + ":%s\\n' \"$c\"";
    }

    static String readResult(InputStream input, String marker) throws IOException {
        Pattern trailer = Pattern.compile("\\n" + Pattern.quote(marker) + ":(\\d+)\\r?\\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            out.write(buffer, 0, count);
            if (out.size() > 65536) throw new IOException("系统返回内容超出限制");
            String result = out.toString(StandardCharsets.UTF_8.name());
            Matcher exit = trailer.matcher(result);
            if (!exit.find()) continue;
            String output = result.substring(0, exit.start()).trim();
            if (!exit.group(1).equals("0"))
                throw new IOException("系统命令执行失败（退出码 " + exit.group(1) + "）" + (output.isEmpty() ? "" : "：\n" + output));
            return output;
        }
        throw new IOException("命令未返回完整执行结果，请重新连接本机 ADB。");
    }
}
