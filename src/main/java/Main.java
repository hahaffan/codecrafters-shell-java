import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {
    private static Path currentDirectory = Paths.get(System.getProperty("user.dir"));

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            String input = scanner.nextLine().trim();

            if (input.equals("exit 0")) {
                break;
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

                if (Files.exists(target) && Files.isDirectory(target)) {
                    currentDirectory = target.normalize();
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
                } else {
                    String pathEnv = System.getenv("PATH");

                    if (pathEnv != null) {
                        String[] paths = pathEnv.split(File.pathSeparator);
                        boolean found = false;

                        for (String path : paths) {
                            File file = new File(path, command);

                            if (file.exists() && file.canExecute()) {
                                System.out.println(command + " is " + file.getAbsolutePath());
                                found = true;
                                break;
                            }
                        }

                        if (!found) {
                            System.out.println(command + ": not found");
                        }
                    } else {
                        System.out.println(command + ": not found");
                    }
                }
                continue;
            }

            System.out.println(input + ": command not found");
        }
    }
}