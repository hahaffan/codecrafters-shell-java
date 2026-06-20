import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {
    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
            "echo", "exit", "type", "pwd", "cd", "jobs"));
    private static int nextJobNumber = 1;
    private static final Map<Integer, Job> backgroundJobs = new LinkedHashMap<>();

    private static class Job {
        int id; Process process; String command;
        Job(int id, Process process, String command) { this.id = id; this.process = process; this.command = command; }
    }

    public static void main(String[] args) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("$ ");
            System.out.flush();
            String line = reader.readLine();
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                runCommand(line);
            } catch (ExitException e) {
                System.exit(e.code);
            } catch (Exception e) {
                // Catching exceptions prevents the shell from exiting on bad input
            }
        }
    }

    private static class Redirection {
        String stdoutFile = null, stderrFile = null;
        boolean stdoutAppend = false, stderrAppend = false;
    }

    private static void runCommand(String line) throws IOException {
        String originalLine = line;
        List<String> tokens = tokenize(line);
        boolean background = !tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&");
        if (background) tokens.remove(tokens.size() - 1);

        Redirection redir = new Redirection();
        tokens = extractRedirections(tokens, redir);
        if (tokens.isEmpty()) return;

        String cmd = tokens.get(0);
        List<String> cmdArgs = tokens.subList(1, tokens.size());

        if (BUILTINS.contains(cmd) && !background) {
            PrintStream originalOut = System.out, originalErr = System.err;
            try {
                if (redir.stdoutFile != null) System.setOut(new PrintStream(new FileOutputStream(redir.stdoutFile, redir.stdoutAppend)));
                if (redir.stderrFile != null) System.setErr(new PrintStream(new FileOutputStream(redir.stderrFile, redir.stderrAppend)));
                dispatch(cmd, cmdArgs);
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        } else {
            handleExternal(cmd, cmdArgs, redir, background, originalLine);
        }
    }

    private static void dispatch(String cmd, List<String> args) {
        switch (cmd) {
            case "exit" -> throw new ExitException(args.isEmpty() ? 0 : Integer.parseInt(args.get(0)));
            case "echo" -> System.out.println(String.join(" ", args));
            case "type" -> { for (String a : args) { if (BUILTINS.contains(a)) System.out.println(a + " is a shell builtin"); else { String f = findInPath(a); System.out.println(f != null ? a + " is " + f : a + ": not found"); } } }
            case "pwd" -> System.out.println(System.getProperty("user.dir"));
            case "cd" -> handleCd(args);
            case "jobs" -> handleJobs();
        }
    }

    private static void handleCd(List<String> args) {
        String target = args.isEmpty() ? System.getenv("HOME") : args.get(0);
        if (target.equals("~")) target = System.getenv("HOME");
        File dir = new File(target);
        if (!dir.isAbsolute()) dir = new File(System.getProperty("user.dir"), target);
        if (!dir.exists()) System.err.println("cd: " + target + ": No such file or directory");
        else System.setProperty("user.dir", dir.getAbsolutePath());
    }

    private static List<String> extractRedirections(List<String> tokens, Redirection redir) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if ((t.equals(">") || t.equals("1>") || t.equals(">>") || t.equals("1>>") || t.equals("2>") || t.equals("2>>")) && i + 1 < tokens.size()) {
                String f = tokens.get(i + 1);
                if (t.contains("1") || t.equals(">") || t.equals(">>")) { redir.stdoutFile = f; redir.stdoutAppend = t.contains(">>"); }
                else { redir.stderrFile = f; redir.stderrAppend = t.contains(">>"); }
                i += 2;
            } else { result.add(t); i++; }
        }
        return result;
    }

    private static void handleJobs() {
        List<Job> list = new ArrayList<>(backgroundJobs.values());
        List<Integer> remove = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Job j = list.get(i);
            String m = (i == list.size() - 1) ? "+" : (i == list.size() - 2) ? "-" : " ";
            if (j.process.isAlive()) System.out.printf("[%d]%s  %-24s%s\n", j.id, m, "Running", j.command);
            else { System.out.printf("[%d]%s  %-24s%s\n", j.id, m, "Done", j.command.replace(" &", "")); remove.add(j.id); }
        }
        for (int id : remove) backgroundJobs.remove(id);
    }

    private static void handleExternal(String cmd, List<String> args, Redirection r, boolean b, String line) throws IOException {
        String path = findInPath(cmd);
        if (path == null) { System.err.println(cmd + ": command not found"); return; }
        List<String> fullCmd = new ArrayList<>(List.of(path)); fullCmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(fullCmd);
        if (r.stdoutFile != null) pb.redirectOutput(r.stdoutAppend ? ProcessBuilder.Redirect.appendTo(new File(r.stdoutFile)) : ProcessBuilder.Redirect.to(new File(r.stdoutFile)));
        if (r.stderrFile != null) pb.redirectError(r.stderrAppend ? ProcessBuilder.Redirect.appendTo(new File(r.stderrFile)) : ProcessBuilder.Redirect.to(new File(r.stderrFile)));
        Process p = pb.start();
        if (b) { int id = nextJobNumber++; backgroundJobs.put(id, new Job(id, p, line)); System.out.println("[" + id + "] " + p.pid()); }
        else { try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }

    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        String[] parts = line.split("(?=[&>])|(?<=[&>])|\\s+");
        for (String p : parts) if (!p.isBlank()) tokens.add(p);
        return tokens;
    }

    private static String findInPath(String cmd) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(":")) {
            File f = new File(dir, cmd);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static class ExitException extends RuntimeException { final int code; ExitException(int code) { this.code = code; } }
}