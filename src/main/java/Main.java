import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static String currentDirectory = System.getProperty("user.dir");

    private static List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (!inSingleQuotes && !inDoubleQuotes && ch == '\\') {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
                continue;
            }

            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }

            if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                continue;
            }

            if (Character.isWhitespace(ch) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static String findExecutable(String command) {
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null) {
            return null;
        }

        String[] directories = pathEnv.split(":");

        for (String directory : directories) {
            Path path = Paths.get(directory, command);

            if (Files.exists(path)
                    && Files.isRegularFile(path)
                    && Files.isExecutable(path)) {
                return path.toString();
            }
        }

        return null;
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine();

            List<String> parts = parseCommand(input);

            if (parts.isEmpty()) {
                continue;
            }

            String command = parts.get(0);

            if (command.equals("exit")) {
                break;
            } else if (command.equals("echo")) {
                for (int i = 1; i < parts.size(); i++) {
                    if (i > 1) {
                        System.out.print(" ");
                    }
                    System.out.print(parts.get(i));
                }
                System.out.println();
            } else if (command.equals("type")) {
                if (parts.size() < 2) {
                    continue;
                }

                String target = parts.get(1);

                if (target.equals("echo")
                        || target.equals("exit")
                        || target.equals("type")
                        || target.equals("pwd")
                        || target.equals("cd")) {

                    System.out.println(target + " is a shell builtin");
                } else {
                    String executable = findExecutable(target);

                    if (executable != null) {
                        System.out.println(target + " is " + executable);
                    } else {
                        System.out.println(target + ": not found");
                    }
                }
            } else if (command.equals("pwd")) {
                System.out.println(currentDirectory);
            } else if (command.equals("cd")) {
                if (parts.size() < 2) {
                    continue;
                }

                String target = parts.get(1);

                if (target.equals("~")) {
                    String home = System.getenv("HOME");
                    if (home != null) {
                        target = home;
                    }
                }

                Path newPath;

                if (Paths.get(target).isAbsolute()) {
                    newPath = Paths.get(target).normalize();
                } else {
                    newPath = Paths.get(currentDirectory, target).normalize();
                }

                File dir = newPath.toFile();

                if (dir.exists() && dir.isDirectory()) {
                    currentDirectory = dir.getAbsolutePath();
                } else {
                    System.out.println("cd: " + target + ": No such file or directory");
                }
            } else {
                try {
                    ProcessBuilder processBuilder = new ProcessBuilder(parts);
                    processBuilder.directory(new File(currentDirectory));
                    processBuilder.inheritIO();

                    Process process = processBuilder.start();
                    process.waitFor();
                } catch (IOException e) {
                    System.out.println(command + ": command not found");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        scanner.close();
    }
}