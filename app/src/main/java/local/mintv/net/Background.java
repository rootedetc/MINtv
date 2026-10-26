package local.mintv.net;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small main-thread callback bridge for the synchronous BFF repositories. */
public final class Background {

    public interface Result<T> {
        void onSuccess(T value);

        default void onError(ApiException error) {
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "mintv-bff");
        thread.setDaemon(true);
        return thread;
    });

    private Background() {
    }

    public static <T> void run(Callable<T> task, Result<T> result) {
        IO.execute(() -> {
            try {
                T value = task.call();
                MAIN.post(() -> result.onSuccess(value));
            } catch (ApiException e) {
                MAIN.post(() -> result.onError(e));
            } catch (Throwable t) {
                MAIN.post(() -> result.onError(new ApiException(
                        ApiException.SERVER, String.valueOf(t), 0, t)));
            }
        });
    }

    public static ExecutorService io() {
        return IO;
    }
}
