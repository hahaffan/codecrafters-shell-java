import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {
    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
            "echo", "exit", "type", "pwd", "cd", "jobs"));
    
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
            // AUTOMATIC REAPING: Check and print done jobs right before the prompt
            checkAndReapJobs(false, System.out);

            System.out.print("$ ");
            System.out.flush();

            line = reader.readLine();
            if (line == null) break;

            line = line.trim();
            if (line.isEmpty()) continue;

            try {
                runCommand(line);
            } catch (ExitException e) {
                System.exit(e.code);
            } catch (Exception e) {
                // Prevent the shell from crashing on a bad command or parsing error
                System.err.println(e.getMessage());
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
        String originalLine = line;
        List<String> tokens = tokenize(line);
        if (tokens.isEmpty()) return;

        boolean background = false;
        if (tokens.get(tokens.size() - 1).equals("&")) {
            background = true;
            tokens.remove(tokens.size() - 1);
        }

        // Split tokens into pipelines
        List<List<String>> pipelines = new ArrayList<>();
        List<String> currentSegment = new ArrayList<>();
        
        for (String t : tokens) {
            if (t.equals("|")) {
                pipelines.add(currentSegment);
                currentSegment = new ArrayList<>();
            } else {
                currentSegment.add(t);
            }
        }
        pipelines.add(currentSegment);

        // handlePipeline natively processes both single commands and complex pipelines
        handlePipeline(pipelines, background, originalLine);
    }

    private static void handlePipeline(List<List<String>> segments, boolean background, String originalLine) throws IOException {
        InputStream prevIn = null;
        List<Process> processes = new ArrayList<>();

        for (int k = 0; k < segments.size(); k++) {
            List<String> segment = segments.get(k);
            Redirection redir = new Redirection();
            segment = extractRedirections(segment, redir);
            if (segment.isEmpty()) continue;

            String cmd = segment.get(0);
            List<String> args = segment.subList(1, segment.size());
            boolean isBuiltin = BUILTINS.contains(cmd);
            boolean isLast = (k == segments.size() - 1);

            if (isBuiltin) {
                // Built-ins in our shell don't read stdin. Close the pipe to signal EOF to the previous process.
                if (prevIn != null) {
                    try { prevIn.close(); } catch(IOException ignored) {}
                }

                PrintStream outTarget = System.out;
                PrintStream errTarget = System.err;
                ByteArrayOutputStream baos = null;

                if (isLast) {
                    if (redir.stdoutFile != null) {
                        outTarget = new PrintStream(new FileOutputStream(redir.stdoutFile, redir.stdoutAppend));
                    }
                } else {
                    baos = new ByteArrayOutputStream();
                    outTarget = new PrintStream(baos);
                }

                if (redir.stderrFile != null) {
                    errTarget = new PrintStream(new FileOutputStream(redir.stderrFile, redir.stderrAppend));
                }

                // Execute builtin synchronously
                dispatch(cmd, args, outTarget, errTarget);
                outTarget.flush();

                if (isLast && outTarget != System.out) outTarget.close();
                if (errTarget != System.err) errTarget.close();

                // Pipe builtin output to the next segment via ByteArrayInputStream
                if (!isLast) {
                    prevIn = new ByteArrayInputStream(baos.toByteArray());
                } else {
                    prevIn = null;
                }

            } else {
                // External Process
                String fullPath = findInPath(cmd);
                if (fullPath == null) {
                    System.err.println(cmd + ": command not found");
                    if (prevIn != null) { try { prevIn.close(); } catch(IOException ignored){} }
                    break; // Abort remaining pipeline but wait for started processes
                }

                List<String> fullCmd = new ArrayList<>();
                fullCmd.add(cmd);
                fullCmd.addAll(args);

                ProcessBuilder pb = new ProcessBuilder(fullCmd);
                pb.environment().put("PATH", System.getenv("PATH"));
                pb.directory(new File(System.getProperty("user.dir")));

                if (prevIn != null) {
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                } else {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                }

                if (redir.stdoutFile != null) {
                    pb.redirectOutput(redir.stdoutAppend ? ProcessBuilder.Redirect.appendTo(new File(redir.stdoutFile)) : ProcessBuilder.Redirect.to(new File(redir.stdoutFile)));
                } else if (isLast) {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                }

                if (redir.stderrFile != null) {
                    pb.redirectError(redir.stderrAppend ? ProcessBuilder.Redirect.appendTo(new File(redir.stderrFile)) : ProcessBuilder.Redirect.to(new File(redir.stderrFile)));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process p = pb.start();
                processes.add(p);

                // Start async thread to pipe previous output into this process's input
                if (prevIn != null) {
                    InputStream finalPrevIn = prevIn;
                    OutputStream pIn = p.getOutputStream();
                    new Thread(() -> {
                        try {
                            finalPrevIn.transferTo(pIn);
                        } catch (IOException ignored) {
                        } finally {
                            try { pIn.close(); } catch (IOException ignored) {}
                            try { finalPrevIn.close(); } catch (IOException ignored) {}
                        }
                    }).start();
                }

                if (!isLast && redir.stdoutFile == null) {
                    prevIn = p.getInputStream();
                } else {
                    prevIn = null;
                }
            }
        }

        if (background && !processes.isEmpty()) {
            int jobId = backgroundJobs.isEmpty() ? 1 : Collections.max(backgroundJobs.keySet()) + 1;
            Process lastProcess = processes.get(processes.size() - 1);
            backgroundJobs.put(jobId, new Job(jobId, lastProcess, originalLine));
            System.out.println("[" + jobId + "] " + lastProcess.pid());
        } else {
            for (Process p : processes) {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void dispatch(String cmd, List<String> args, PrintStream out, PrintStream err) {
        switch (cmd) {
            case "exit" -> throw new ExitException(args.isEmpty() ? 0 : Integer.parseInt(args.get(0)));
            case "echo" -> out.println(String.join(" ", args));
            case "type" -> {
                for (String a : args) {
                    if (BUILTINS.contains(a)) {
                        out.println(a + " is a shell builtin");
                    } else {
                        String f = findInPath(a);
                        out.println(f != null ? a + " is " + f : a + ": not found");
                    }
                }
            }
            case "pwd" -> out.println(System.getProperty("user.dir"));
            case "cd" -> handleCd(args, err);
            case "jobs" -> handleJobs(out);
        }
    }

    private static void handleCd(List<String> args, PrintStream err) {
        String target = args.isEmpty() ? System.getenv("HOME") : args.get(0);
        if (target == null) target = "/";
        if (target.equals("~")) target = System.getenv("HOME");
        if (target == null) target = "/";

        File dir = new File(target);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), target);
        }

        if (!dir.exists()) {
            err.println("cd: " + target + ": No such file or directory");
        } else if (!dir.isDirectory()) {
            err.println("cd: " + target + ": Not a directory");
        } else {
            System.setProperty("user.dir", dir.toPath().normalize().toAbsolutePath().toString());
        }
    }

    private static List<String> extractRedirections(List<String> tokens, Redirection redir) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if ((t.equals(">") || t.equals("1>") || t.equals(">>") || t.equals("1>>") || t.equals("2>") || t.equals("2>>")) && i + 1 < tokens.size()) {
                String f = tokens.get(i + 1);
                if (t.contains("1") || t.equals(">") || t.equals(">>")) {
                    redir.stdoutFile = f;
                    redir.stdoutAppend = t.contains(">>");
                } else {
                    redir.stderrFile = f;
                    redir.stderrAppend = t.contains(">>");
                }
                i += 2; 
            } else {
                result.add(t);
                i++;
            }
        }
        return result;
    }

    private static void handleJobs(PrintStream out) {
        checkAndReapJobs(true, out);
    }

    private static void checkAndReapJobs(boolean printRunning, PrintStream out) {
        List<Job> list = new ArrayList<>(backgroundJobs.values());
        List<Integer> toRemove = new ArrayList<>();
        int size = list.size();
        
        for (int i = 0; i < size; i++) {
            Job job = list.get(i);
            String marker = (i == size - 1) ? "+" : (i == size - 2) ? "-" : " ";
            
            if (job.process.isAlive()) {
                // If called by `jobs`, print the running jobs. If called before prompt, stay silent.
                if (printRunning) {
                    out.printf("[%d]%s  %-24s%s\n", job.id, marker, "Running", job.command);
                }
            } else {
                // Always print and reap finished jobs
                String cmdStr = job.command;
                if (cmdStr.endsWith("&")) {
                    cmdStr = cmdStr.substring(0, cmdStr.length() - 1).trim();
                }
                out.printf("[%d]%s  %-24s%s\n", job.id, marker, "Done", cmdStr);
                toRemove.add(job.id);
            }
        }
        
        // Purge completed jobs
        for (Integer id : toRemove) {
            backgroundJobs.remove(id);
        }
    }

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

            if (c == '|') { tokens.add("|"); i++; continue; }
            if (c == '&') { tokens.add("&"); i++; continue; }
            if (c == '1' && i + 2 < len && line.charAt(i + 1) == '>' && line.charAt(i + 2) == '>') { tokens.add("1>>"); i += 3; continue; }
            if (c == '1' && i + 1 < len && line.charAt(i + 1) == '>') { tokens.add("1>"); i += 2; continue; }
            if (c == '2' && i + 2 < len && line.charAt(i + 1) == '>' && line.charAt(i + 2) == '>') { tokens.add("2>>"); i += 3; continue; }
            if (c == '2' && i + 1 < len && line.charAt(i + 1) == '>') { tokens.add("2>"); i += 2; continue; }
            if (c == '>' && i + 1 < len && line.charAt(i + 1) == '>') { tokens.add(">>"); i += 2; continue; }
            if (c == '>') { tokens.add(">"); i++; continue; }

            StringBuilder token = new StringBuilder();

            while (i < len && !isWhitespace(line.charAt(i))) {
                c = line.charAt(i);

                if (c == '&' || c == '>' || c == '|') break;
                if ((c == '1' || c == '2') && i + 1 < len && line.charAt(i + 1) == '>') break;

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
                            if (next == '"' || next == '\\' || next == '$' || next == '`' || next == '\n') {
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

    private static class ExitException extends RuntimeException {
        final int code;
        ExitException(int code) {
            this.code = code;
        }
    }
}