import com.dan.kejian.SigninWebViewProfile;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Simulates the provider surviving Activity destruction without using a school website. */
public final class ProfileLifecycleTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger configured = new AtomicInteger();
        if (args[0].equals("reopen")) {
            final boolean[] providerStarted = {false};
            for (int activity = 0; activity < 100; activity++) {
                SigninWebViewProfile.initialize(() -> {
                    if (providerStarted[0]) throw new IllegalStateException("WebView provider already initialized");
                    configured.incrementAndGet();
                });
                providerStarted[0] = true;
            }
        } else if (args[0].equals("retry")) {
            try { SigninWebViewProfile.initialize(() -> { throw new IllegalStateException("initial setup failed"); }); }
            catch (IllegalStateException expected) { }
            SigninWebViewProfile.initialize(configured::incrementAndGet);
        } else {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread[] threads = new Thread[16];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> { try { SigninWebViewProfile.initialize(configured::incrementAndGet); } catch (Throwable error) { failure.set(error); } });
                threads[i].start();
            }
            for (Thread thread : threads) thread.join();
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
        if (configured.get() != 1) throw new AssertionError("profile initialization count: " + configured);
        System.out.println("PASS " + args[0]);
    }
}
