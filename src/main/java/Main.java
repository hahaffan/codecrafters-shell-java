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

            if (input.isEmpty()) {
                continue;
            }

            String[] parts = input.split("\\s+");
            String command = parts[0];

            if (command.equals("exit")) {
                if (parts.length > 1 && parts[1].equals("0")) {
                    break;
                }
                break;
            }

            if (command.equals("echo")) {
                System.out.println(input.substring(5));
                continue;
            }

            if (command.equals("pwd")) {
                System.out.println(currentDirectory.toString());
                continue;
            }

            if (command.equals("cd")) {

                if (parts.length < 2) {
                    continue;
                }

                String dir = parts[1];

                Path target = Paths.get(dir);

                if (Files.exists(target) && Files.isDirectory(target)) {
                    currentDirectory = target.toAbsolutePath().normalize();
                } else {
                    System.out.println(
                            "cd: " + dir + ": No such file or directory");
                }

                continue;
            }

            if (command.equals("type")) {

                if (parts.length < 2) {
                    continue;
                }

                String target = parts[1];

                if (target.equals("echo")
                        || target.equals("exit")
                        || target.equals("type")
                        || target.equals("pwd")
                        || target.equals("cd")) {

                    System.out.println(target + " is a shell builtin");
                    continue;
                }

                String executable = findExecutable(target);

                if (executable != null) {
                    System.out.println(target + " is " + executable);
                } else {
                    System.out.println(target + ": not found");
                }

                continue;
            }

            String executable = findExecutable(command);

            if (executable != null) {

                List<String> cmd = new ArrayList<>();

                cmd.add(executable);

                for (int i = 1; i < parts.length; i++) {
                    cmd.add(parts[i]);
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(currentDirectory.toFile());

                Process process = pb.start();

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        process.getInputStream()));

                String line;

                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }

                process.waitFor();

            } else {
                System.out.println(command + ": command not found");
            }
        }
    }

    private static String findExecutable(String command) {

        String pathEnv = System.getenv("PATH");

        if (pathEnv == null) {
            return null;
        }

        String[] directories = pathEnv.split(":");

        for (String dir : directories) {

            File file = new File(dir, command);

            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }
}