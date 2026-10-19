package kz.carlauncher;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 11;
    private static final int REQ_CAR_PHOTO = 12;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HashMap<String, String> iconCache = new HashMap<>();
    private LocationManager lm;
    private boolean locationStarted = false;

    private MediaSessionManager msm;
    private MediaController controller;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.setLongClickable(false);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { return true; }
        });
        web.setHapticFeedbackEnabled(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        hideBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        startMediaWatch();
        js("window.onResume&&onResume()");
    }

    @Override
    protected void onDestroy() {
        stopLocation();
        stopMediaWatch();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    public void onBackPressed() {
        // Launcher: back closes overlays in the page, never exits.
        js("window.onBack&&onBack()");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home button pressed while already home -> close overlays.
        js("window.onBack&&onBack()");
    }

    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void js(final String code) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    // ---------------------------------------------------------------- location

    private void startLocation() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (locationStarted) return;
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000, 0, locListener, Looper.getMainLooper());
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            }
            locationStarted = true;
            if (best != null) sendLocation(best);
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
    }

    private void stopLocation() {
        try { if (lm != null) lm.removeUpdates(locListener); } catch (Exception ignored) {}
        locationStarted = false;
    }

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { sendLocation(l); }
        @Override public void onStatusChanged(String p, int st, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private void sendLocation(Location l) {
        js("window.onLocation&&onLocation(" + l.getLatitude() + "," + l.getLongitude() + ","
                + (l.hasAltitude() ? l.getAltitude() : "null") + ","
                + l.getBearing() + "," + (l.hasSpeed() ? l.getSpeed() : 0) + ","
                + (l.hasBearing() ? "true" : "false") + ")");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocation();
        }
    }

    // ---------------------------------------------------------------- weather

    private void fetchWeather(final double lat, final double lon) {
        new Thread(new Runnable() {
            @Override public void run() {
                String city = "";
                try {
                    if (Geocoder.isPresent()) {
                        List<Address> a = new Geocoder(MainActivity.this, new Locale("ru")).getFromLocation(lat, lon, 1);
                        if (a != null && !a.isEmpty()) {
                            Address ad = a.get(0);
                            city = ad.getLocality() != null ? ad.getLocality()
                                    : (ad.getSubAdminArea() != null ? ad.getSubAdminArea() : ad.getAdminArea());
                        }
                    }
                } catch (Exception ignored) {}
                try {
                    String u = String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                    + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,surface_pressure,uv_index"
                                    + "&wind_speed_unit=ms&timezone=auto", lat, lon);
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    c.disconnect();
                    js("window.onWeather&&onWeather(" + q(sb.toString()) + "," + q(city) + ")");
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // ---------------------------------------------------------------- media

    private boolean hasMusicAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private void startMediaWatch() {
        if (Build.VERSION.SDK_INT < 21 || !hasMusicAccess()) {
            js("window.onMusicAccess&&onMusicAccess(false)");
            return;
        }
        try {
            if (msm == null) msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            final ComponentName cn = new ComponentName(this, MediaListener.class);
            if (sessionsListener == null) {
                sessionsListener = new MediaSessionManager.OnActiveSessionsChangedListener() {
                    @Override public void onActiveSessionsChanged(List<MediaController> list) { pickController(list); }
                };
                msm.addOnActiveSessionsChangedListener(sessionsListener, cn);
            }
            pickController(msm.getActiveSessions(cn));
        } catch (SecurityException e) {
            js("window.onMusicAccess&&onMusicAccess(false)");
        } catch (Exception ignored) {}
    }

    private void stopMediaWatch() {
        try {
            if (msm != null && sessionsListener != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
            if (controller != null) controller.unregisterCallback(mediaCb);
        } catch (Exception ignored) {}
        sessionsListener = null;
        controller = null;
    }

    private void pickController(List<MediaController> list) {
        MediaController chosen = null;
        if (list != null) {
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { chosen = mc; break; }
            }
            if (chosen == null && !list.isEmpty()) chosen = list.get(0);
        }
        if (controller != null) {
            try { controller.unregisterCallback(mediaCb); } catch (Exception ignored) {}
        }
        controller = chosen;
        if (controller != null) {
            controller.registerCallback(mediaCb, ui);
        }
        pushMedia(true);
    }

    private final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { pushMedia(true); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { pushMedia(false); }
        @Override public void onSessionDestroyed() {
            controller = null;
            pushMedia(true);
        }
    };

    private void pushMedia(boolean withArt) {
        String title = "", artist = "", app = "", art = null;
        boolean playing = false;
        if (controller != null) {
            MediaMetadata md = controller.getMetadata();
            if (md != null) {
                title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
                if (withArt) {
                    Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    art = b != null ? toDataUri(b, 220, true) : "";
                }
            }
            PlaybackState st = controller.getPlaybackState();
            playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            app = labelOf(controller.getPackageName());
        } else if (withArt) {
            art = "";
        }
        js("window.onMedia&&onMedia(" + q(title) + "," + q(artist) + "," + q(app) + ","
                + (art == null ? "null" : q(art)) + "," + playing + ")");
    }

    private void mediaCommand(String cmd) {
        if (controller != null) {
            MediaController.TransportControls t = controller.getTransportControls();
            PlaybackState st = controller.getPlaybackState();
            switch (cmd) {
                case "prev": t.skipToPrevious(); return;
                case "next": t.skipToNext(); return;
                default:
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
                    return;
            }
        }
        int code = "prev".equals(cmd) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                : "next".equals(cmd) ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        }
    }

    // ---------------------------------------------------------------- apps & icons

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }

    private static Bitmap drawableToBitmap(Drawable d, int size) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), size, size, true);
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return b;
    }

    private static String toDataUri(Bitmap b, int maxSide, boolean jpeg) {
        int w = b.getWidth(), h = b.getHeight();
        float k = Math.min(1f, (float) maxSide / Math.max(w, h));
        if (k < 1f) b = Bitmap.createScaledBitmap(b, Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)), true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 85, bos);
        return (jpeg ? "data:image/jpeg;base64," : "data:image/png;base64,")
                + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    private File carFile() { return new File(getFilesDir(), "car.png"); }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAR_PHOTO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    InputStream in = getContentResolver().openInputStream(uri);
                    BitmapFactory.decodeStream(in, null, o);
                    if (in != null) in.close();
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / sample > 1600) sample *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    in = getContentResolver().openInputStream(uri);
                    Bitmap b = BitmapFactory.decodeStream(in, null, o2);
                    if (in != null) in.close();
                    if (b == null) return;
                    float k = Math.min(1f, 1200f / Math.max(b.getWidth(), b.getHeight()));
                    if (k < 1f) b = Bitmap.createScaledBitmap(b, (int) (b.getWidth() * k), (int) (b.getHeight() * k), true);
                    FileOutputStream fos = new FileOutputStream(carFile());
                    b.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    js("window.onCarPhoto&&onCarPhoto()");
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // ---------------------------------------------------------------- JS bridge

    public class Bridge {

        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "1.0"; }
        }

        @JavascriptInterface
        public String getApps() {
            JSONArray arr = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
                HashMap<String, Boolean> seen = new HashMap<>();
                for (ResolveInfo ri : list) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || seen.containsKey(pkg)) continue;
                    seen.put(pkg, true);
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("label", String.valueOf(ri.loadLabel(pm)));
                    arr.put(o);
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        @JavascriptInterface
        public String getIcon(String pkg) {
            synchronized (iconCache) {
                String c = iconCache.get(pkg);
                if (c != null) return c;
            }
            String r = "";
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                r = toDataUri(drawableToBitmap(d, 96), 96, false);
            } catch (Exception ignored) {}
            synchronized (iconCache) { iconCache.put(pkg, r); }
            return r;
        }

        @JavascriptInterface
        public boolean isInstalled(String pkg) {
            try {
                return getPackageManager().getLaunchIntentForPackage(pkg) != null;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean launch(String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return false;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void openSystemSettings() {
            startSafe(new Intent(Settings.ACTION_SETTINGS));
        }

        @JavascriptInterface
        public void openHomeSettings() {
            if (Build.VERSION.SDK_INT >= 21 && startSafe(new Intent(Settings.ACTION_HOME_SETTINGS))) return;
            startSafe(new Intent(Settings.ACTION_SETTINGS));
        }

        @JavascriptInterface
        public void openMusicAccess() {
            if (!startSafe(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))) {
                startSafe(new Intent(Settings.ACTION_SETTINGS));
            }
        }

        @JavascriptInterface
        public boolean hasMusicAccess() {
            return MainActivity.this.hasMusicAccess();
        }

        @JavascriptInterface
        public void media(final String cmd) {
            ui.post(new Runnable() { @Override public void run() { mediaCommand(cmd); } });
        }

        @JavascriptInterface
        public void pickCarPhoto() {
            ui.post(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("image/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    try { startActivityForResult(Intent.createChooser(i, "Фото машины"), REQ_CAR_PHOTO); }
                    catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public String getCarPhoto() {
            File f = carFile();
            if (!f.exists()) return "";
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            return b == null ? "" : toDataUri(b, 1200, false);
        }

        @JavascriptInterface
        public void clearCarPhoto() {
            //noinspection ResultOfMethodCallIgnored
            carFile().delete();
        }

        @JavascriptInterface
        public void requestLocation() {
            ui.post(new Runnable() { @Override public void run() { startLocation(); } });
        }

        @JavascriptInterface
        public void fetchWeather(double lat, double lon) {
            MainActivity.this.fetchWeather(lat, lon);
        }
    }

    private boolean startSafe(final Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
