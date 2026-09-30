/** Minimal application for scripts: sleeps for the given number of milliseconds. */
public class Sleep {
  public static void main(String[] args) throws Exception {
    Thread.sleep(Long.parseLong(args[0]));
  }
}
