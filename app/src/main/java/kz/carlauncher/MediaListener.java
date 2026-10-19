package kz.carlauncher;

import android.service.notification.NotificationListenerService;

/** Needed only so the user can grant "notification access", which lets the
 *  launcher read the currently playing track (title, artist, cover). */
public class MediaListener extends NotificationListenerService {
}
