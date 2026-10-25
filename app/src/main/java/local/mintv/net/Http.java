package local.mintv.net;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** One shared OkHttp client for API calls, image loading and Media3 data sources. */
public final class Http {

    private static volatile OkHttpClient client;

    private Http() {
    }

    public static OkHttpClient client() {
        OkHttpClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (Http.class) {
            if (client == null) {
                client = new OkHttpClient.Builder()
                        .connectTimeout(12, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .writeTimeout(20, TimeUnit.SECONDS)
                        .callTimeout(30, TimeUnit.SECONDS)
                        // The MAXtv CDN redirects manifests to short-lived token URLs.
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .retryOnConnectionFailure(true)
                        .build();
            }
            return client;
        }
    }
}
