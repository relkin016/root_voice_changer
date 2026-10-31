package ru.relkin.rootvoice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Один долгоживущий процесс su: команды пишутся в stdin, конец вывода отмечается маркером. */
final class RootShell {
    static final class Result {
        final int code;
        final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out;
        }

        boolean ok() {
            return code == 0;
        }
    }

    private static Process proc;
    private static Writer in;
    private static BufferedReader out;

    private RootShell() {
    }

    /** Выполняет скрипт от root; stderr объединён с stdout. */
    static synchronized Result run(String script) throws IOException {
        ensure();
        String marker = "__RVC_END_" + System.nanoTime() + "__";
        try {
            in.write("(\n" + script + "\n) </dev/null 2>&1\necho \"" + marker + "$?\"\n");
            in.flush();
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = out.readLine()) != null) {
                int i = line.indexOf(marker);
                if (i >= 0) {
                    sb.append(line, 0, i);
                    int code;
                    try {
                        code = Integer.parseInt(line.substring(i + marker.length()).trim());
                    } catch (NumberFormatException e) {
                        code = -1;
                    }
                    return new Result(code, sb.toString());
                }
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            close();
            throw e;
        }
        close();
        throw new IOException("root-доступ не выдан");
    }

    /** true, если su есть и выдал uid 0. Первый вызов показывает запрос root-менеджера. */
    static boolean available() {
        try {
            Result r = run("id -u");
            return r.ok() && r.out.trim().equals("0");
        } catch (IOException e) {
            return false;
        }
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void ensure() throws IOException {
        if (proc != null && proc.isAlive()) return;
        proc = new ProcessBuilder("su").redirectErrorStream(true).start();
        in = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);
        out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
    }

    private static void close() {
        if (proc != null) proc.destroy();
        proc = null;
        in = null;
        out = null;
    }
}
