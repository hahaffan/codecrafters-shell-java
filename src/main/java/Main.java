import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {

    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
            "echo", "exit", "type", "pwd", "cd"
    ));

    public static void main(String[] args) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            line = reader.readLine();
            if (line == null) {
                break;
            }

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
    }

    // -------------------------------------------------------------------------
    // Command dispatch
    // -------------------------------------------------------------------------

    private static void runCommand(String line) throws IOException {
        line = line.trim();
        if (line.isEmpty()) {
            return;
        }

        List<String> tokens = tokenize(line);
        if (tokens.isEmpty()) {
            return;
        }

        Redirection redir = new Redirection();
        tokens = extractRedirections(tokens, redir);

        if (tokens.isEmpty()) {
            return;
        }

        String cmd = tokens.get(0);
        List<String> cmdArgs = tokens.subList(1, tokens.size());

        if (redir.stdoutFile != null && BUILTINS.contains(cmd)) {
            PrintStream oldOut = System.out;
            PrintStream fileOut = new PrintStream(
                    new FileOutputStream(redir.stdoutFile, false)
            );

            System.setOut(fileOut);

            try {
                dispatch(cmd, cmdArgs, redir);
            } finally {
                System.out.flush();
                System.setOut(oldOut);
                fileOut.close();
            }
        } else {
            dispatch(cmd, cmdArgs, redir);
        }
    }

    private static void dispatch(String cmd, List<String> cmdArgs, Redirection redir) throws IOException {
        switch (cmd) {
            case "exit" -> handleExit(cmdArgs);
            case "echo" -> handleEcho(cmdArgs);
            case "type" -> handleType(cmdArgs);
            case "pwd" -> handlePwd();
            case "cd" -> handleCd(cmdArgs);
            default -> handleExternal(cmd, cmdArgs, redir);
        }
    }

    // -------------------------------------------------------------------------
    // Redirection extraction
    // -------------------------------------------------------------------------

    private static List<String> extractRedirections(List<String> tokens, Redirection redir) {
        List<String> result = new ArrayList<>();

        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);

            if ((token.equals(">") || token.equals("1>")) && i + 1 < tokens.size()) {
                redir.stdoutFile = tokens.get(i + 1);
                i += 2;
            } else {
                result.add(token);
                i++;
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Built-ins
    // -------------------------------------------------------------------------

    private static void handleExit(List<String> args) {
        int code = 0;

        if (!args.isEmpty()) {
            code = Integer.parseInt(args.get(0));
        }

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
                String path = findInPath(arg);

                if (path != null) {
                    System.out.println(arg + " is " + path);
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
        String target;

        if (args.isEmpty()) {
            target = System.getenv("HOME");
        } else {
            target = args.get(0);
        }

        if (target == null) {
            target = "/";
        }

        if (target.equals("~")) {
            target = System.getenv("HOME");
        }

        if (target == null) {
            target = "/";
        }

        File dir = new File(target);

        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), target);
        }

        if (!dir.exists()) {
            System.err.println("cd: " + target + ": No such file or directory");
            return;
        }

        if (!dir.isDirectory()) {
            System.err.println("cd: " + target + ": Not a directory");
            return;
        }

        System.setProperty(
                "user.dir",
                dir.toPath().normalize().toAbsolutePath().toString()
        );
    }

    // -------------------------------------------------------------------------
    // External commands
    // -------------------------------------------------------------------------

    private static void handleExternal(String cmd, List<String> args, Redirection redir)
            throws IOException {

        String fullPath = findInPath(cmd);

        if (fullPath == null) {
            System.err.println(cmd + ": command not found");
            return;
        }

        List<String> command = new ArrayList<>();
        command.add(fullPath);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(System.getProperty("user.dir")));

        if (redir.stdoutFile != null) {
            pb.redirectOutput(new File(redir.stdoutFile));
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        } else {
            pb.inheritIO();
        }

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

    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();

        int i = 0;

        while (i < line.length()) {
            char c = line.charAt(i);

            if (c == '\'') {
                i++;

                while (i < line.length() && line.charAt(i) != '\'') {
                    token.append(line.charAt(i));
                    i++;
                }

                if (i < line.length()) {
                    i++;
                }

                continue;
            }

            if (c == '"') {
                i++;

                while (i < line.length() && line.charAt(i) != '"') {
                    if (line.charAt(i) == '\\' && i + 1 < line.length()) {
                        char next = line.charAt(i + 1);

                        if (next == '"' || next == '\\' || next == '$' || next == '`') {
                            token.append(next);
                            i += 2;
                        } else {
                            token.append('\\');
                            i++;
                        }
                    } else {
                        token.append(line.charAt(i));
                        i++;
                    }
                }

                if (i < line.length()) {
                    i++;
                }

                continue;
            }

            if (c == '\\') {
                if (i + 1 < line.length()) {
                    token.append(line.charAt(i + 1));
                    i += 2;
                } else {
                    i++;
                }

                continue;
            }

            if (c == '1' && i + 1 < line.length() && line.charAt(i + 1) == '>') {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }

                tokens.add("1>");
                i += 2;
                continue;
            }

            if (c == '>') {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }

                tokens.add(">");
                i++;
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }

                i++;
                continue;
            }

            token.append(c);
            i++;
        }

        if (token.length() > 0) {
            tokens.add(token.toString());
        }

        return tokens;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String findInPath(String cmd) {
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null) {
            return null;
        }

        String[] dirs = pathEnv.split(":");

        for (String dir : dirs) {
            Path fullPath = Paths.get(dir, cmd);
            File file = fullPath.toFile();

            if (file.isFile() && file.canExecute()) {
                return fullPath.toString();
            }
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Exit exception
    // -------------------------------------------------------------------------

    private static class ExitException extends RuntimeException {
        final int code;

        ExitException(int code) {
            this.code = code;
        }
    }
}