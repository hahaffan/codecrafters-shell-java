import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

public class Main {
    
    // 1. Manually track the current working directory
    private static Path currentDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        // The Shell REPL (Read-Eval-Print Loop)
        while (true) {
            System.out.print("$ ");
            
            if (!scanner.hasNextLine()) {
                break;
            }
            
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            // Split the input into command and arguments
            String[] commandArgs = input.split("\\s+");
            String command = commandArgs[0];

            switch (command) {
                case "exit":
                    if (commandArgs.length > 1 && commandArgs[1].equals("0")) {
                        System.exit(0);
                    }
                    break;
                    
                case "pwd":
                    // 2. pwd must print our tracked directory, NOT a cached initial directory
                    System.out.println(currentDir.toString());
                    break;
                    
                case "cd":
                    handleCd(commandArgs);
                    break;
                    
                // Add your previous stage implementations (echo, type, external execution) here
                
                default:
                    System.out.println(command + ": command not found");
            }
        }
        scanner.close();
    }

    private static void handleCd(String[] args) {
        if (args.length < 2) {
            return; 
        }

        String pathStr = args[1];
        Path targetDir = Paths.get(pathStr);

        // 3. Attempt the directory change
        if (Files.exists(targetDir) && Files.isDirectory(targetDir)) {
            // Update the tracked directory variable
            currentDir = targetDir.normalize();
            
            // Optional but highly recommended: Update the JVM's property 
            // so any ProcessBuilder you use later defaults to this new directory
            System.setProperty("user.dir", currentDir.toString());
        } else {
            // Print the exact error string the tester expects
            System.out.printf("cd: %s: No such file or directory\n", pathStr);
        }
    }
}