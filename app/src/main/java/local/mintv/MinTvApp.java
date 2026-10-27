package local.mintv;

import android.app.Application;

import local.mintv.ui.ImageLoader;

/**
 * Process entry point. Binds the shared image disk cache before the first activity so a
 * cold start can paint channel logos and artwork without touching the network.
 */
public final class MinTvApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ImageLoader.init(this);
    }
}
