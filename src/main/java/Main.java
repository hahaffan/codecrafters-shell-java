import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Shell {

    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList("echo", "exit", "type", "pwd", "cd"));

    public static void main(String[] args) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            line = reader.readLine();
            if (line == null) break; // EOF

            try {
                runCommand(line);
            } catch (ExitException e) {
                System.exit(e.code);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Command dispatch
    // -------------------------------------------------------------------------

    private static void runCommand(String line) throws IOException {
        line = line.trim();
        if (line.isEmpty()) return;

        List<String> tokens = tokenize(line);
        if (tokens.isEmpty()) return;

        String cmd  = tokens.get(0);
        List<String> cmdArgs = tokens.subList(1, tokens.size());

        switch (cmd) {
            case "exit" -> handleExit(cmdArgs);
            case "echo" -> handleEcho(cmdArgs);
            case "type" -> handleType(cmdArgs);
            case "pwd"  -> handlePwd();
            case "cd"   -> handleCd(cmdArgs);
            default     -> handleExternal(cmd, cmdArgs);
        }
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
            System.out.println("cd: " + target + ": No such file or directory");
        } else if (!dir.isDirectory()) {
            System.out.println("cd: " + target + ": Not a directory");
        } else {
            System.setProperty("user.dir", dir.toPath().normalize().toAbsolutePath().toString());
        }
    }

    private static void handleExternal(String cmd, List<String> args) throws IOException {
        String fullPath = findInPath(cmd);
        if (fullPath == null) {
            System.out.println(cmd + ": command not found");
            return;
        }

        List<String> command = new ArrayList<>();
        command.add(fullPath);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.inheritIO();

        Process process = pb.start();
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Tokenizer
    // -------------------------------------------------------------------------

    /**
     * Splits a command line into tokens, respecting single quotes, double
     * quotes, and backslash escaping (both inside and outside quotes).
     */
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int len = line.length();

        while (i < len) {
            // Skip whitespace between tokens
            while (i < len && isWhitespace(line.charAt(i))) i++;
            if (i >= len) break;

            // Parse one token (may be made of adjacent quoted/unquoted parts)
            StringBuilder token = new StringBuilder();
            while (i < len && !isWhitespace(line.charAt(i))) {
                char c = line.charAt(i);

                if (c == '\'') {
                    // Single-quote: everything literal until closing '
                    i++;
                    while (i < len && line.charAt(i) != '\'') {
                        token.append(line.charAt(i++));
                    }
                    if (i < len) i++; // consume closing '

                } else if (c == '"') {
                    // Double-quote: backslash only escapes special chars
                    i++;
                    while (i < len && line.charAt(i) != '"') {
                        if (line.charAt(i) == '\\' && i + 1 < len) {
                            char next = line.charAt(i + 1);
                            if (next == '"' || next == '\\' || next == '$'
                                    || next == '`' || next == '\n') {
                                token.append(next);
                                i += 2;
                            } else {
                                // Backslash kept literally
                                token.append('\\');
                                i++;
                            }
                        } else {
                            token.append(line.charAt(i++));
                        }
                    }
                    if (i < len) i++; // consume closing "

                } else if (c == '\\') {
                    // Outside quotes: backslash escapes next char
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
            tokens.add(token.toString());
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
        if (pathEnv == null) return null;

        for (String dir : pathEnv.split(":")) {
            Path full = Paths.get(dir, cmd);
            File file = full.toFile();
            if (file.isFile() && file.canExecute()) {
                return full.toString();
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Internal exception used to exit cleanly
    // -------------------------------------------------------------------------

    private static class ExitException extends RuntimeException {
        final int code;
        ExitException(int code) { this.code = code; }
    }
}