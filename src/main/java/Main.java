import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    private static Path currentDirectory = Paths.get(System.getProperty("user.dir"));

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine().trim();

            if (input.equals("exit") || input.equals("exit 0")) {
                break;
            }

            if (input.equals("echo")) {
                System.out.println();
                continue;
            }

            if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
                continue;
            }

            if (input.equals("pwd")) {
                System.out.println(currentDirectory.toString());
                continue;
            }

            if (input.startsWith("cd ")) {
                String dir = input.substring(3).trim();

                Path target = Paths.get(dir);

                if (!target.isAbsolute()) {
                    target = currentDirectory.resolve(target);
                }

                target = target.normalize();

                if (Files.exists(target) && Files.isDirectory(target)) {
                    currentDirectory = target;
                } else {
                    System.out.println("cd: " + dir + ": No such file or directory");
                }

                continue;
            }

            if (input.startsWith("type ")) {
                String command = input.substring(5).trim();

                if (command.equals("echo")
                        || command.equals("exit")
                        || command.equals("type")
                        || command.equals("pwd")
                        || command.equals("cd")) {
                    System.out.println(command + " is a shell builtin");
                    continue;
                }

                String pathEnv = System.getenv("PATH");
                boolean found = false;

                if (pathEnv != null) {
                    String[] paths = pathEnv.split(File.pathSeparator);

                    for (String path : paths) {
                        File file = new File(path, command);

                        if (file.exists() && file.canExecute()) {
                            System.out.println(command + " is " + file.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }
                }

                if (!found) {
                    System.out.println(command + ": not found");
                }

                continue;
            }

            String[] parts = input.split("\\s+");
            String command = parts[0];

            String pathEnv = System.getenv("PATH");
            boolean executed = false;

            if (pathEnv != null) {
                String[] paths = pathEnv.split(File.pathSeparator);

                for (String path : paths) {
                    File file = new File(path, command);

                    if (file.exists() && file.canExecute()) {
                        List<String> cmd = new ArrayList<>();
                        cmd.add(command);

                        for (int i = 1; i < parts.length; i++) {
                            cmd.add(parts[i]);
                        }

                        ProcessBuilder pb = new ProcessBuilder(cmd);
                        pb.directory(currentDirectory.toFile());
                        pb.inheritIO();
                        pb.environment().put("PATH", pathEnv);

                        Process process = pb.start();
                        process.waitFor();

                        executed = true;
                        break;
                    }
                }
            }

            if (!executed) {
                System.out.println(command + ": command not found");
            }
        }

        scanner.close();
    }
}