import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {

    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
            "echo", "exit", "type", "pwd", "cd", "jobs"));

    private static int nextJobNumber = 1;

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

    // -------------------------------------------------------------------------
    // Redirection info
    // -------------------------------------------------------------------------

    private static class Redirection {
        String stdoutFile = null;
        String stderrFile = null;
        boolean stdoutAppend = false;
        boolean stderrAppend = false;
    }

    // -------------------------------------------------------------------------
    // Command dispatch
    // -------------------------------------------------------------------------

    private static void runCommand(String line) throws IOException {
        line = line.trim();
        if (line.isEmpty()) return;

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

            dispatch(cmd, cmdArgs, redir, background);

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
                                 Redirection redir, boolean background) throws IOException {
        switch (cmd) {
            case "exit" -> handleExit(cmdArgs);
            case "echo" -> handleEcho(cmdArgs);
            case "type" -> handleType(cmdArgs);
            case "pwd" -> handlePwd();
            case "cd" -> handleCd(cmdArgs);
            case "jobs" -> handleJobs();
            default -> handleExternal(cmd, cmdArgs, redir, background);
        }
    }

    // -------------------------------------------------------------------------
    // Redirection extraction
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Built-in commands
    // -------------------------------------------------------------------------

    private static void handleExit(List<String> args) {
        int code = args.isEmpty() ? 0 : Integer.parseInt(args.get(0));
        throw new ExitException(code);
    }

    private static void handleEcho(List<String> args) {
        System.out.println(String.join(" ", args));
    }

    private static void handleType(List<String> args) {
        for (String arg : args) {
            if (BUILTINS.contains(arg)) {
                System.out.println(arg + " is a shell builtin");
            } else {
                String found = findInPath(arg);
                if (found != null) {
                    System.out.println(arg + " is " + found);
                } else {
                    System.out.println(arg + ": not found");
                }
            }
        }
    }

    private static void handlePwd() {
        System.out.println(System.getProperty("user.dir"));
    }

    private static void handleCd(List<String> args) {
        String target = args.isEmpty() ? System.getenv("HOME") : args.get(0);

        if (target == null) target = "/";
        if (target.equals("~")) target = System.getenv("HOME");
        if (target == null) target = "/";

        File dir = new File(target);

        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), target);
        }

        if (!dir.exists()) {
            System.err.println("cd: " + target + ": No such file or directory");
        } else if (!dir.isDirectory()) {
            System.err.println("cd: " + target + ": Not a directory");
        } else {
            System.setProperty(
                    "user.dir",
                    dir.toPath().normalize().toAbsolutePath().toString()
            );
        }
    }

    // -------------------------------------------------------------------------
    // Jobs
    // -------------------------------------------------------------------------

    private static void handleJobs() {
        // Not required for this stage
    }

    // -------------------------------------------------------------------------
    // External commands
    // -------------------------------------------------------------------------

    private static void handleExternal(String cmd, List<String> args,
                                       Redirection redir, boolean background) throws IOException {

        String fullPath = findInPath(cmd);

        if (fullPath == null) {
            System.err.println(cmd + ": command not found");
            return;
        }

        List<String> command = new ArrayList<>();
        command.add(cmd);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PATH", System.getenv("PATH"));
        pb.directory(new File(System.getProperty("user.dir")));

        // This INHERIT logic natively handles Stage #SI2's background output requirement!
        if (redir.stdoutFile != null) {
            pb.redirectOutput(
                    redir.stdoutAppend
                            ? ProcessBuilder.Redirect.appendTo(new File(redir.stdoutFile))
                            : ProcessBuilder.Redirect.to(new File(redir.stdoutFile))
            );
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT); 
        }

        if (redir.stderrFile != null) {
            pb.redirectError(
                    redir.stderrAppend
                            ? ProcessBuilder.Redirect.appendTo(new File(redir.stderrFile))
                            : ProcessBuilder.Redirect.to(new File(redir.stderrFile))
            );
        } else {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        }

        Process process = pb.start();

        if (background) {
            System.out.println("[" + nextJobNumber++ + "] " + process.pid());
            return;
        }

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Tokenizer
    // -------------------------------------------------------------------------

    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();

        int i = 0;
        int len = line.length();

        while (i < len) {

            while (i < len && isWhitespace(line.charAt(i))) {
                i++;
            }

            if (i >= len) break;

            char c = line.charAt(i);

            if (c == '&') {
                tokens.add("&");
                i++;
                continue;
            }

            if (c == '1' && i + 2 < len &&
                    line.charAt(i + 1) == '>' &&
                    line.charAt(i + 2) == '>') {
                tokens.add("1>>");
                i += 3;
                continue;
            }

            if (c == '1' && i + 1 < len &&
                    line.charAt(i + 1) == '>') {
                tokens.add("1>");
                i += 2;
                continue;
            }

            if (c == '2' && i + 2 < len &&
                    line.charAt(i + 1) == '>' &&
                    line.charAt(i + 2) == '>') {
                tokens.add("2>>");
                i += 3;
                continue;
            }

            if (c == '2' && i + 1 < len &&
                    line.charAt(i + 1) == '>') {
                tokens.add("2>");
                i += 2;
                continue;
            }

            if (c == '>' && i + 1 < len &&
                    line.charAt(i + 1) == '>') {
                tokens.add(">>");
                i += 2;
                continue;
            }

            if (c == '>') {
                tokens.add(">");
                i++;
                continue;
            }

            StringBuilder token = new StringBuilder();

            while (i < len && !isWhitespace(line.charAt(i))) {

                c = line.charAt(i);

                if (c == '&') {
                    break;
                }

                if (c == '>') break;

                if ((c == '1' || c == '2')
                        && i + 1 < len
                        && line.charAt(i + 1) == '>') {
                    break;
                }

                if (c == '\'') {

                    i++;

                    while (i < len && line.charAt(i) != '\'') {
                        token.append(line.charAt(i++));
                    }

                    if (i < len) i++;

                } else if (c == '"') {

                    i++;

                    while (i < len && line.charAt(i) != '"') {

                        if (line.charAt(i) == '\\' && i + 1 < len) {

                            char next = line.charAt(i + 1);

                            if (next == '"' ||
                                    next == '\\' ||
                                    next == '$' ||
                                    next == '`' ||
                                    next == '\n') {

                                token.append(next);
                                i += 2;

                            } else {

                                token.append('\\');
                                i++;
                            }

                        } else {

                            token.append(line.charAt(i++));
                        }
                    }

                    if (i < len) i++;

                } else if (c == '\\') {

                    if (i + 1 < len) {
                        token.append(line.charAt(i + 1));
                        i += 2;
                    } else {
                        token.append(c);
                        i++;
                    }

                } else {

                    token.append(c);
                    i++;
                }
            }

            if (token.length() > 0) {
                tokens.add(token.toString());
            }
        }

        return tokens;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t';
    }

    private static String findInPath(String cmd) {
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null) {
            return null;
        }

        for (String dir : pathEnv.split(":")) {
            Path full = Paths.get(dir, cmd);
            File file = full.toFile();

            if (file.isFile() && file.canExecute()) {
                return full.toString();
            }
        }

        return null;
    }

    private static class ExitException extends RuntimeException {
        final int code;

        ExitException(int code) {
            this.code = code;
        }
    }
}