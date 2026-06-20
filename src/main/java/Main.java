import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// 'public' keyword removed so this can stay in Main.java
class Builtins {
    
    public static Path currentDir = Paths.get(System.getProperty("user.dir"));

    public static void handleCd(String[] args) {
        if (args.length < 2) {
            return;
        }

        String pathStr = args[1];
        Path targetDir = Paths.get(pathStr);

        if (Files.exists(targetDir) && Files.isDirectory(targetDir)) {
            currentDir = targetDir;
            System.setProperty("user.dir", targetDir.toAbsolutePath().toString());
        } else {
            System.out.printf("cd: %s: No such file or directory\n", pathStr);
        }
    }
}