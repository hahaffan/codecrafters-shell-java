import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {
    private static Path currentDirectory =
            Paths.get(System.getProperty("user.dir"));

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine().trim();

            if (input.equals("exit 0")) {
                break;
            }

            if (input.startsWith("cd ")) {
                String dir = input.substring(3).trim();

                Path target = Paths.get(dir);

                if (Files.exists(target) && Files.isDirectory(target)) {
                    currentDirectory = target.normalize();
                } else {
                    System.out.println(
                            "cd: " + dir + ": No such file or directory");
                }

                continue;
            }

            if (input.equals("pwd")) {
                System.out.println(currentDirectory.toString());
                continue;
            }

            if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
                continue;
            }

            System.out.println(input + ": command not found");
        }
    }
}