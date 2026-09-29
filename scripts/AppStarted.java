/**
 * Stand-in for an application: it only proves that its own main() ran. Used by
 * scripts/smoke-test.sh to check that a broken agent cannot stop the application from starting.
 */
public class AppStarted {
  public static void main(String[] args) {
    System.out.println("APPLICATION STARTED");
  }
}
