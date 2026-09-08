package io.github.haydenkz.meshcorehelper;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import java.util.List;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

/** Message alerts are separate from the quiet, ongoing BLE service notification. */
public final class MessageNotifications {
    public static final String CHANNELS = "channel_messages";
    public static final String DIRECT = "direct_messages";
    public static final String OPEN_CHAT = "io.github.haydenkz.meshcorehelper.OPEN_CHAT";
    public static final String KIND = "conversation_kind";
    public static final String CONVERSATION = "conversation_id";
    private static final int MESSAGE_ID = 2; // Distinct from foreground service ID 1.
    private static volatile String visibleConversation;
    private MessageNotifications() {}

    public static void createChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channels = new NotificationChannel(CHANNELS, "Channel messages", NotificationManager.IMPORTANCE_DEFAULT);
        channels.setDescription("New messages received in MeshCore channels");
        NotificationChannel direct = new NotificationChannel(DIRECT, "Direct messages", NotificationManager.IMPORTANCE_DEFAULT);
        direct.setDescription("New direct messages received from MeshCore contacts");
        manager.createNotificationChannels(List.of(channels, direct));
    }
    public static boolean enabled(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager.areNotificationsEnabled() && allowed(manager, CHANNELS) && allowed(manager, DIRECT);
    }
    private static boolean allowed(NotificationManager manager, String id) {
        NotificationChannel channel = manager.getNotificationChannel(id);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    private static String tag(String kind, String id) { return "message:" + kind + ":" + id; }
    public static void visible(Context context, String kind, String id) {
        visibleConversation = kind == null || id == null ? null : tag(kind, id);
        if (visibleConversation != null) context.getSystemService(NotificationManager.class).cancel(visibleConversation, MESSAGE_ID);
    }
    public static boolean validConversation(String kind, String id) {
        if (kind == null || id == null) return false;
        return kind.equals("channel") ? id.matches("[0-9a-f]{64}:[0-9]{1,3}") && Integer.parseInt(id.substring(65)) <= 255
                : kind.equals("direct") && id.matches("[0-9a-f]{64}:[0-9a-f]{12}");
    }
    public static Intent openIntent(Context context, String kind, String id) {
        // Data participates in PendingIntent identity; extras alone would cross-wire chats.
        return new Intent(context, MainActivity.class).setAction(OPEN_CHAT)
                .setData(new Uri.Builder().scheme("meshcore").authority("chat").appendPath(kind).appendPath(id).build())
                .putExtra(KIND, kind).putExtra(CONVERSATION, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    public static void received(Context context, String radio, ReceivedMessage message, String name) {
        String id = radio + ":" + message.peer();
        if (!validConversation(message.kind(), id)) return;
        String tag = tag(message.kind(), id);
        if (tag.equals(visibleConversation)) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        createChannels(context);
        String channel = message.kind().equals("channel") ? CHANNELS : DIRECT;
        if (!manager.areNotificationsEnabled() || !allowed(manager, channel)) return;
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(new Person.Builder().setName("You").build());
        if (message.kind().equals("channel")) style.setConversationTitle(name);
        style.setGroupConversation(message.kind().equals("channel"));
        // Reuse only still-visible messages so dismissed/read alerts never reappear.
        for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
            if (!tag.equals(active.getTag())) continue;
            NotificationCompat.MessagingStyle old = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(active.getNotification());
            if (old != null) {
                List<NotificationCompat.MessagingStyle.Message> previous = old.getMessages();
                for (int i = Math.max(0, previous.size() - 5); i < previous.size(); i++) style.addMessage(previous.get(i));
            }
        }
        String sender = message.sender().isBlank() ? (message.kind().equals("direct") ? name : "Unknown sender") : message.sender();
        style.addMessage(message.text(), message.sentAt(), new Person.Builder().setName(sender).build());
        PendingIntent open = PendingIntent.getActivity(context, 0, openIntent(context, message.kind(), id), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_messages).setContentTitle(name).setContentText(message.text())
                .setCategory(Notification.CATEGORY_MESSAGE).setStyle(style).setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setWhen(System.currentTimeMillis()).build();
        try { manager.notify(tag, MESSAGE_ID, notification); }
        catch (SecurityException ignored) { /* Permission may be revoked while a packet arrives. */ }
    }
}
