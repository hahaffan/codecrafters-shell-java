import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {

    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
            "echo", "exit", "type", "pwd", "cd", "jobs"));

    private static int nextJobNumber = 1;
    
    // LinkedHashMap preserves insertion order, which is critical for marker logic
    private static final Map<Integer, Job> backgroundJobs = new LinkedHashMap<>();

    private static class Job {
        int id;
        Process process;
        String command;

        Job(int id, Process process, String command) {
            this.id = id;
            this.process = process;
            this.command = command;
        }
    }

    public static void main(String[] args) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            line = reader.readLine();
            if (line == null) break;

            try {
                runCommand(line);
            } catch (ExitException e) {
                System.exit(e.code);
            }
        }
    }

    private static class Redirection {
        String stdoutFile = null;
        String stderrFile = null;
        boolean stdoutAppend = false;
        boolean stderrAppend = false;
    }

    private static void runCommand(String line) throws IOException {
        line = line.trim();
        if (line.isEmpty()) return;

        String originalLine = line;
        List<String> tokens = tokenize(line);
        if (tokens.isEmpty()) return;

        boolean background = false;
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals("&")) {
            background = true;
            tokens.remove(tokens.size() - 1);
        }

        Redirection redir = new Redirection();
        tokens = extractRedirections(tokens, redir);
        if (tokens.isEmpty()) return;

        String cmd = tokens.get(0);
        List<String> cmdArgs = tokens.subList(1, tokens.size());

        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        PrintStream fileOut = null;
        PrintStream fileErr = null;

        try {
            if (redir.stdoutFile != null) {
                fileOut = new PrintStream(new FileOutputStream(redir.stdoutFile, redir.stdoutAppend));
                System.setOut(fileOut);
            }

            if (redir.stderrFile != null) {
                fileErr = new PrintStream(new FileOutputStream(redir.stderrFile, redir.stderrAppend));
                System.setErr(fileErr);
            }

            dispatch(cmd, cmdArgs, redir, background, originalLine);

        } finally {
            if (fileOut != null) {
                System.out.flush();
                System.setOut(oldOut);
                fileOut.close();
            }

            if (fileErr != null) {
                System.err.flush();
                System.setErr(oldErr);
                fileErr.close();
            }
        }
    }

    private static void dispatch(String cmd, List<String> cmdArgs,
                                 Redirection redir, boolean background, String originalLine) throws IOException {
        switch (cmd) {
            case "exit" -> handleExit(cmdArgs);
            case "echo" -> handleEcho(cmdArgs);
            case "type" -> handleType(cmdArgs);
            case "pwd" -> handlePwd();
            case "cd" -> handleCd(cmdArgs);
            case "jobs" -> handleJobs();
            default -> handleExternal(cmd, cmdArgs, redir, background, originalLine);
        }
    }

    private static List<String> extractRedirections(List<String> tokens, Redirection redir) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if ((t.equals(">") || t.equals("1>")) && i + 1 < tokens.size()) {
                redir.stdoutFile = tokens.get(i + 1);
                redir.stdoutAppend = false;
                i += 2;
            } else if ((t.equals(">>") || t.equals("1>>")) && i + 1 < tokens.size()) {
                redir.stdoutFile = tokens.get(i + 1);
                redir.stdoutAppend = true;
                i += 2;
            } else if (t.equals("2>") && i + 1 < tokens.size()) {
                redir.stderrFile = tokens.get(i + 1);
                redir.stderrAppend = false;
                i += 2;
            } else if (t.equals("2>>") && i + 1 < tokens.size()) {
                redir.stderrFile = tokens.get(i + 1);
                redir.stderrAppend = true;
                i += 2;
            } else {
                result.add(t);
                i++;
            }
        }
        return result;
    }

    private static void handleExit(List<String> args) {
        throw new ExitException(args.isEmpty() ? 0 : Integer.parseInt(args.get(0)));
    }

    private static void handleEcho(List<String> args) {
        System.out.println(String.join(" ", args));
    }

    private static void handleType(List<String> args) {
        for (String arg : args) {
            if (BUILTINS.contains(arg)) System.out.println(arg + " is a shell builtin");
            else {
                String found = findInPath(arg);
                System.out.println(found != null ? arg + " is " + found : arg + ": not found");
            }
        }
    }

    private static void handlePwd() { System.out.println(System.getProperty("user.dir")); }

    private static void handleCd(List<String> args) {
        String target = args.isEmpty() ? System.getenv("HOME") : args.get(0);
        if (target == null) target = "/";
        if (target.equals("~")) target = System.getenv("HOME");
        File dir = new File(target);
        if (!dir.isAbsolute()) dir = new File(System.getProperty("user.dir"), target);
        if (!dir.exists()) System.err.println("cd: " + target + ": No such file or directory");
        else if (!dir.isDirectory()) System.err.println("cd: " + target + ": Not a directory");
        else System.setProperty("user.dir", dir.toPath().normalize().toAbsolutePath().toString());
    }

    private static void handleJobs() {
        List<Job> jobsList = new ArrayList<>(backgroundJobs.values());
        List<Integer> toRemove = new ArrayList<>();
        int size = jobsList.size();
        for (int i = 0; i < size; i++) {
            Job job = jobsList.get(i);
            String marker = (i == size - 1) ? "+" : (i == size - 2) ? "-" : " ";
            if (job.process.isAlive()) {
                System.out.printf("[%d]%s  %-24s%s\n", job.id, marker, "Running", job.command);
            } else {
                String cmd = job.command.endsWith("&") ? job.command.substring(0, job.command.length() - 1).trim() : job.command;
                System.out.printf("[%d]%s  %-24s%s\n", job.id, marker, "Done", cmd);
                toRemove.add(job.id);
            }
        }
        for (Integer id : toRemove) backgroundJobs.remove(id);
    }

    private static void handleExternal(String cmd, List<String> args, Redirection redir, boolean background, String originalLine) throws IOException {
        String fullPath = findInPath(cmd);
        if (fullPath == null) {
            System.err.println(cmd + ": command not found");
            return;
        }
        List<String> command = new ArrayList<>(List.of(fullPath));
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(redir.stdoutFile != null ? (redir.stdoutAppend ? ProcessBuilder.Redirect.appendTo(new File(redir.stdoutFile)) : ProcessBuilder.Redirect.to(new File(redir.stdoutFile))) : ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(redir.stderrFile != null ? (redir.stderrAppend ? ProcessBuilder.Redirect.appendTo(new File(redir.stderrFile)) : ProcessBuilder.Redirect.to(new File(redir.stderrFile))) : ProcessBuilder.Redirect.INHERIT);
        Process process = pb.start();
        if (background) {
            int jobId = nextJobNumber++;
            backgroundJobs.put(jobId, new Job(jobId, process, originalLine));
            System.out.println("[" + jobId + "] " + process.pid());
        } else {
            try { process.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        int i = 0, len = line.length();
        while (i < len) {
            while (i < len && isWhitespace(line.charAt(i))) i++;
            if (i >= len) break;
            char c = line.charAt(i);
            if (c == '&') { tokens.add("&"); i++; continue; }
            if (c == '>') { tokens.add(">"); i++; continue; }
            StringBuilder token = new StringBuilder();
            while (i < len && !isWhitespace(line.charAt(i)) && line.charAt(i) != '&' && line.charAt(i) != '>') {
                token.append(line.charAt(i++));
            }
            if (token.length() > 0) tokens.add(token.toString());
        }
        return tokens;
    }

    private static boolean isWhitespace(char c) { return c == ' ' || c == '\t'; }

    private static String findInPath(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(":")) {
            Path full = Paths.get(dir, cmd);
            if (full.toFile().isFile() && full.toFile().canExecute()) return full.toString();
        }
        return null;
    }

    private static class ExitException extends RuntimeException {
        final int code;
        ExitException(int code) { this.code = code; }
    }
}