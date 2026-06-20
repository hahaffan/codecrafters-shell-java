import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String command = scanner.nextLine();
            System.out.println(command + ": command not found")
        }
    }
}