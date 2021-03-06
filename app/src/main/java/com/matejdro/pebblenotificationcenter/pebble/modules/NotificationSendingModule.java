package com.matejdro.pebblenotificationcenter.pebble.modules;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.util.SparseArray;

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.matejdro.pebblecommons.pebble.CommModule;
import com.matejdro.pebblecommons.pebble.PebbleCapabilities;
import com.matejdro.pebblecommons.pebble.PebbleCommunication;
import com.matejdro.pebblecommons.pebble.PebbleImageToolkit;
import com.matejdro.pebblecommons.pebble.PebbleTalkerService;
import com.matejdro.pebblecommons.pebble.PebbleUtil;
import com.matejdro.pebblecommons.util.DeviceUtil;
import com.matejdro.pebblecommons.util.TextUtil;
import com.matejdro.pebblecommons.vibration.PebbleVibrationPattern;
import com.matejdro.pebblenotificationcenter.GeneralNCDatabase;
import com.matejdro.pebblenotificationcenter.NCTalkerService;
import com.matejdro.pebblenotificationcenter.PebbleNotification;
import com.matejdro.pebblenotificationcenter.PebbleNotificationCenter;
import com.matejdro.pebblenotificationcenter.ProcessedNotification;
import com.matejdro.pebblenotificationcenter.R;
import com.matejdro.pebblenotificationcenter.appsetting.AppSetting;
import com.matejdro.pebblenotificationcenter.appsetting.AppSettingStorage;
import com.matejdro.pebblenotificationcenter.appsetting.PebbleAppNotificationMode;
import com.matejdro.pebblenotificationcenter.notifications.JellybeanNotificationListener;
import com.matejdro.pebblenotificationcenter.notifications.actions.DismissOnPebbleAction;
import com.matejdro.pebblenotificationcenter.notifications.actions.NotificationAction;
import com.matejdro.pebblenotificationcenter.notifications.actions.ReplaceNotificationAction;
import com.matejdro.pebblenotificationcenter.pebble.NativeNotificationIcon;
import com.matejdro.pebblenotificationcenter.pebble.NotificationCenterDeveloperConnection;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import timber.log.Timber;

/**
 * Created by Matej on 28.11.2014.
 */
public class NotificationSendingModule extends CommModule
{
    public static final int MODULE_NOTIFICATION_SENDING = 1;
    public static final String INTENT_NOTIFICATION = "Notification";
    public static final String INTENT_MUTE_APP_TEMPORARILY = "MuteAppTemporarily";
    public static final String INTENT_CLEAR_TEMPORARY_MUTES = "ClearTemporaryMutes";

    public static final int DEFAULT_TEXT_LIMIT = 2000;

    private static Queue<PebbleNotification> processingQueue = new ConcurrentLinkedQueue<>();

    private HashMap<String, Long> lastAppVibration = new HashMap<String, Long>();
    private HashMap<String, Long> lastAppNotification = new HashMap<String, Long>();
    private HashMap<String, Long> temporaryMutes = new HashMap<String, Long>();
    private ProcessedNotification curSendingNotification;
    private Queue<ProcessedNotification> sendingQueue = new LinkedList<>();

    public NotificationSendingModule(PebbleTalkerService service)
    {
        super(service);
        service.registerIntent(INTENT_NOTIFICATION, this);
        service.registerIntent(INTENT_MUTE_APP_TEMPORARILY, this);
        service.registerIntent(INTENT_CLEAR_TEMPORARY_MUTES, this);
    }

    private FilteringResult shouldFilterNotification(PebbleNotification notificationSource)
    {
        AppSettingStorage settingStorage = notificationSource.getSettingStorage(getService());

        String combinedText = notificationSource.getTitle() + "\n" + notificationSource.getSubtitle() + "\n" + notificationSource.getText();
        List<String> regexList = settingStorage.getStringList(AppSetting.INCLUDED_REGEX);
        if (regexList.size() > 0 && !TextUtil.containsRegexes(combinedText, regexList))
        {
            Timber.d("notify failed - whitelist regex");
            return FilteringResult.ONLY_KEEP_TEMPORARY;
        }

        regexList = settingStorage.getStringList(AppSetting.EXCLUDED_REGEX);
        if (TextUtil.containsRegexes(combinedText, regexList))
        {
            Timber.d("notify failed - blacklist regex");
            return FilteringResult.ONLY_KEEP_TEMPORARY;
        }

        if (!settingStorage.getBoolean(AppSetting.SEND_BLANK_NOTIFICATIONS)) {
            if (notificationSource.getText().trim().isEmpty() && (notificationSource.getSubtitle() == null || notificationSource.getSubtitle().trim().isEmpty())) {
                Timber.d("notify failed - empty");
                return FilteringResult.IGNORE;
            }
        }


        if (getService().getGlobalSettings().getBoolean(PebbleNotificationCenter.NOTIFICATIONS_DISABLED, false))
            return FilteringResult.ONLY_SAVE_TO_HISTORY;

        if (settingStorage.getBoolean(AppSetting.DISABLE_NOTIFY_SCREEN_OIN))
        {
            if (DeviceUtil.isScreenOn(getService()))
            {
                Timber.d("notify failed - screen is on");
                return FilteringResult.ONLY_SAVE_TO_HISTORY;
            }
        }

        if (getService().getGlobalSettings().getBoolean(PebbleNotificationCenter.NO_NOTIFY_VIBRATE, false))
        {
            AudioManager am = (AudioManager) getService().getSystemService(Context.AUDIO_SERVICE);
            if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL)
            {
                Timber.d("notify failed - ringer is silent");
                return FilteringResult.ONLY_SAVE_TO_HISTORY;
            }

        }

        if (settingStorage.getBoolean(AppSetting.QUIET_TIME_ENABLED))
        {
            int startHour = settingStorage.getInt(AppSetting.QUIET_TIME_START_HOUR);
            int startMinute = settingStorage.getInt(AppSetting.QUIET_TIME_START_MINUTE);
            int startTime = startHour * 60 + startMinute;

            int endHour = settingStorage.getInt(AppSetting.QUIET_TIME_END_HOUR);
            int endMinute = settingStorage.getInt(AppSetting.QUIET_TIME_END_MINUTE);
            int endTime = endHour * 60 + endMinute;

            Calendar calendar = Calendar.getInstance();
            int curHour = calendar.get(Calendar.HOUR_OF_DAY);
            int curMinute = calendar.get(Calendar.MINUTE);
            int curTime = curHour * 60 + curMinute;


            if ((endTime > startTime && curTime <= endTime && curTime >= startTime) || (endTime < startTime && (curTime <= endTime || curTime >= startTime)))
            {
                Timber.d("notify failed - quiet time");
                return FilteringResult.ONLY_SAVE_TO_HISTORY;
            }
        }

        if (getService().getGlobalSettings().getBoolean("noNotificationsNoPebble", false) && !isWatchConnected(getService()))
        {
            Timber.d("notify failed - watch not connected");
            return FilteringResult.ONLY_SAVE_TO_HISTORY;
        }

        if (settingStorage.getBoolean(AppSetting.RESPECT_ANDROID_INTERRUPT_FILTER) && JellybeanNotificationListener.isNotificationFilteredByDoNotInterrupt(notificationSource.getKey()))
        {
            Timber.d("notify failed - interrupt filter");
            return FilteringResult.ONLY_SAVE_TO_HISTORY;
        }

        int minNotificationInterval = 0;
        try
        {
            minNotificationInterval = Integer.parseInt(settingStorage.getString(AppSetting.MINIMUM_NOTIFICATION_INTERVAL));
        }
        catch (NumberFormatException e)
        {
        }

        Long appMutedUntil = temporaryMutes.get(notificationSource.getKey().getPackage());
        if (appMutedUntil != null)
        {
            if (appMutedUntil > System.currentTimeMillis())
            {
                Timber.d("notify failed - temporary app filter");
                return FilteringResult.IGNORE;
            }
            else
            {
                temporaryMutes.remove(notificationSource.getKey().getPackage());
            }
        }

        if (minNotificationInterval > 0) {
            Long lastNotification = lastAppNotification.get(notificationSource.getKey().getPackage());
            if (lastNotification != null) {
                if ((System.currentTimeMillis() - lastNotification) < minNotificationInterval * 1000) {
                    Timber.d("notification ignored - minimum interval not passed!");
                    return FilteringResult.ONLY_SAVE_TO_HISTORY;
                }
            }
        }

        if (!canDisplayWearGroupNotification(notificationSource, settingStorage))
        {
            Timber.d("notify failed - group");
            return FilteringResult.ONLY_KEEP_TEMPORARY;
        }

        return FilteringResult.SEND;
    }

    public void processNotification(PebbleNotification notificationSource)
    {
        Timber.d("notify internal");

        ProcessedNotification notification = new ProcessedNotification();
        notification.source = notificationSource;
        AppSettingStorage settingStorage = notificationSource.getSettingStorage(getService());

        String customTitle = settingStorage.getString(AppSetting.CUSTOM_TITLE);

        if (!customTitle.isEmpty())
        {
            if (customTitle.trim().isEmpty()) //Space in title
            {
                notificationSource.setTitle(notificationSource.getSubtitle());
                notificationSource.setSubtitle(null);
            }
            else
            {
                notificationSource.setTitle(customTitle);
            }
        }
        
        if (notificationSource.getSubtitle().isEmpty())
        {
            //Attempt to figure out subtitle
            String subtitle = "";
            String text = notificationSource.getText();

            if (text.contains("\n"))
            {
                int firstLineBreak = text.indexOf('\n');
                if (firstLineBreak < text.length() * 0.8)
                {
                    subtitle = text.substring(0, firstLineBreak).trim();
                    text = text.substring(firstLineBreak).trim();
                }
            }

            notificationSource.setText(text);
            notificationSource.setSubtitle(subtitle);
        }

        if (notificationSource.getTitle().trim().equals(notificationSource.getSubtitle().trim()))
            notificationSource.setSubtitle("");



        FilteringResult filteringResult = FilteringResult.SEND;
        if (!notificationSource.isListNotification())
            filteringResult = shouldFilterNotification(notificationSource);

        if ((filteringResult == FilteringResult.SEND || filteringResult == FilteringResult.ONLY_SAVE_TO_HISTORY) &&
                !notificationSource.isHistoryDisabled() && !notificationSource.isListNotification() &&
                settingStorage.getBoolean(AppSetting.SAVE_TO_HISTORY))
        {
            NCTalkerService.fromPebbleTalkerService(getService()).getHistoryDatabase().storeNotification(notificationSource.getRawPostTime(),
                    TextUtil.trimString(notificationSource.getTitle(), 4000, true),
                    TextUtil.trimString(notificationSource.getSubtitle(), 4000, true),
                    TextUtil.trimString(notificationSource.getText(), 4000, true),
                    notificationSource.getNotificationIcon());
        }


        if (filteringResult != FilteringResult.SEND && filteringResult != FilteringResult.ONLY_KEEP_TEMPORARY)
            return;

        int colorFromConfig = settingStorage.getInt(AppSetting.STATUSBAR_COLOR);
        if (Color.alpha(colorFromConfig) != 0)
            notificationSource.setColor(colorFromConfig);

        NativeNotificationIcon iconFromConfig = settingStorage.getEnum(AppSetting.NATIVE_NOTIFICATION_ICON);
        if (iconFromConfig != NativeNotificationIcon.AUTOMATIC)
            notificationSource.setNativeNotificationIcon(iconFromConfig);

        SparseArray<ProcessedNotification> sentNotifications = NCTalkerService.fromPebbleTalkerService(getService()).sentNotifications;

        Random rnd = new Random();
        do
        {
            //Notifications 0-9 are special reserved
            //(only 0 = BT disconnected notification used for now)
            notification.id = rnd.nextInt(Integer.MAX_VALUE - 10) + 10;
        }
        while (sentNotifications.get(notification.id) != null);

        if (filteringResult == FilteringResult.ONLY_KEEP_TEMPORARY)
        {
            // Sometimes notifications should be filtered out to not be displayed on the pebble,
            // but they need to be keep in as if they were sent to prevent other wear group notifications
            // on replacing them
            sentNotifications.put(notification.id, notification);
            return;
        }

        //Notification replacing should not be performed for any list notifications
        if (!notification.source.isListNotification())
        {
            int lastDismissedID = DismissUpwardsModule.get(getService()).processDismissUpwards(notificationSource.getKey(), false);
            if (settingStorage.getBoolean(AppSetting.NO_UPDATE_VIBRATION))
                notification.prevId = lastDismissedID;

            DismissUpwardsModule.get(getService()).dismissSimilarWearNotifications(notification, false);
        }


        notification.wasSentToWatch = true;
        if (settingStorage.getBoolean(AppSetting.HIDE_NOTIFICATION_TEXT) && !notificationSource.isListNotification() && !notificationSource.isHidingTextDisallowed())
            sendNotificationAsPrivate(notification);
        else
            sendNotification(notification);

        NCTalkerService.fromPebbleTalkerService(getService()).getHistoryDatabase().tryCleanDatabase();
    }

    private void notificationTransferCompleted()
    {
        if (curSendingNotification.vibrated)
            lastAppVibration.put(curSendingNotification.source.getKey().getPackage(), System.currentTimeMillis());
        lastAppNotification.put(curSendingNotification.source.getKey().getPackage(), System.currentTimeMillis());

        curSendingNotification = null;
    }

    private void sendNotificationAsPrivate(ProcessedNotification notification)
    {
        PebbleNotification coverNotification = new PebbleNotification(notification.source.getTitle(), "Use Show action to uncover it.", notification.source.getKey());
        coverNotification.setSubtitle("Hidden notification");
        coverNotification.setHidingTextDisallowed(true);
        coverNotification.setNoHistory(true);
        coverNotification.setWearGroupKey(notification.source.getWearGroupKey());
        coverNotification.setWearGroupType(notification.source.getWearGroupType());

        ArrayList<NotificationAction> actions = new ArrayList<>();
        actions.add(new ReplaceNotificationAction("Show", notification));
        actions.add(new DismissOnPebbleAction(getService()));
        coverNotification.setActions(actions);

        processNotification(coverNotification);
    }

    public void sendNotification(ProcessedNotification notification)
    {
        NCTalkerService.fromPebbleTalkerService(getService()).sentNotifications.put(notification.id, notification);

        int pebbleAppMode = PebbleAppNotificationMode.OPEN_IN_NOTIFICATION_CENTER;
        if (!notification.source.isListNotification())
        {
            //Different type of notification depending on Pebble app
            SystemModule systemModule = SystemModule.get(getService());
            systemModule.updateCurrentlyRunningApp();

            UUID currentApp = systemModule.getCurrentRunningApp();
            Timber.d("Current app: ", currentApp);
            pebbleAppMode = GeneralNCDatabase.getInstance().getPebbleAppNotificationMode(currentApp);
        }

        if (pebbleAppMode == PebbleAppNotificationMode.SHOW_NATIVE_NOTIFICATION && !getService().getDeveloperConnection().isOpen())
        {
            // Fallback to NC mode when developer connection is not available. Also notify user about the problem.

            notification.source.setText(notification.source.getText() + getService().getString(R.string.native_to_nc_fallback_notice));
            pebbleAppMode = PebbleAppNotificationMode.OPEN_IN_NOTIFICATION_CENTER;
        }

        if (pebbleAppMode == PebbleAppNotificationMode.OPEN_IN_NOTIFICATION_CENTER)
        {
            sendNCNotification(notification);
        }
        else if (pebbleAppMode == PebbleAppNotificationMode.SHOW_NATIVE_NOTIFICATION)
        {
            sendNativeNotification(notification);
        }
        else if (pebbleAppMode == PebbleAppNotificationMode.DISABLE_NOTIFICATION) //No notification
        {
            Timber.d("notify failed - pebble app");
        }
    }

    private void sendNativeNotification(ProcessedNotification notification)
    {
        Timber.d("Sending native notification...");

        notification.nativeNotification = true;

        PebbleKit.FirmwareVersionInfo watchfirmware = PebbleUtil.getPebbleFirmwareVersion(getService());
        if (watchfirmware == null)
        {
            return;
        }

        if (watchfirmware.getMajor() > 2)
        {
            NotificationCenterDeveloperConnection.fromDevConn(getService().getDeveloperConnection()).sendSDK3Notification(notification, true);
        }
        else if (watchfirmware.getMajor() == 2 && watchfirmware.getMinor() > 8)
        {
            NotificationCenterDeveloperConnection.fromDevConn(getService().getDeveloperConnection()).sendNotification(notification);
        }
        else
        {
            getService().getDeveloperConnection().sendBasicNotification(notification.source.getText(), notification.source.getSubtitle() + "\n" + notification.source.getText());
        }

    }

    private void sendNCNotification(ProcessedNotification notification)
    {
        Timber.d("SendNC");

        notification.nativeNotification = false;

        //Split text into chunks
        int textLimit = getMaximumTextLength(notification.source.getSettingStorage(getService()));
        String mergedText = notification.source.getTitle() + "\0" + notification.source.getSubtitle() + "\0" + notification.source.getText();
        mergedText = TextUtil.prepareString(mergedText, textLimit);

        byte[] textBytes = mergedText.getBytes();
        notification.textLength = (short) textBytes.length;

        boolean lookingForSubtitleIndex = true;
        for (int i = 0; i < textBytes.length; i++)
        {
            if (textBytes[i] == 0)
            {
                if (lookingForSubtitleIndex)
                {
                    notification.firstSubtitleIndex = (short) (i + 1);
                    lookingForSubtitleIndex = false;
                }
                else
                {
                    notification.firstTextIndex = (short) (i + 1);
                    break;
                }
            }
        }

        int i = 0;
        while (i < textBytes.length)
        {
            byte[] chunk = new byte[100];
            int size = Math.min(100, textBytes.length - i);
            System.arraycopy(textBytes, i, chunk, 0, size);
            notification.textChunks.add(chunk);

            i+= size;
        }

        Timber.d("BeginSend %d %s %s %d", notification.id, notification.source.getTitle(), notification.source.getSubtitle(), notification.textChunks.size());

        SystemModule.get(getService()).openApp();

        sendingQueue.add(notification);

        PebbleCommunication communication = getService().getPebbleCommunication();
        communication.queueModulePriority(this);
        communication.sendNext();
    }

    private void sendInitialNotificationPacket()
    {
        ProcessedNotification notificationToSend = sendingQueue.peek();

        Timber.d("Initial notify packet %d", notificationToSend.id);

        notificationToSend.nextChunkToSend = 0;
        notificationToSend.waitingForConfirmation = true;

        AppSettingStorage settingStorage = notificationToSend.source.getSettingStorage(getService());

        int periodicVibrationInterval = 0;
        try
        {
            periodicVibrationInterval = Math.min(Integer.parseInt(settingStorage.getString(AppSetting.PERIODIC_VIBRATION)), 30000);
        } catch (NumberFormatException e)
        {
        }

        PebbleDictionary data = new PebbleDictionary();
        List<Byte> vibrationPattern = getVibrationPattern(notificationToSend, settingStorage);

        int amountOfActions = 0;
        if (notificationToSend.source.getActions() != null)
            amountOfActions = notificationToSend.source.getActions().size();

        boolean showMenuInstantly = getService().getGlobalSettings().getBoolean("showMenuInstantly", true);

        byte flags = 0;
        flags |= (byte) (notificationToSend.source.isListNotification() ? 0x2 : 0);
        flags |= (byte) ((settingStorage.getBoolean(AppSetting.SWITCH_TO_MOST_RECENT_NOTIFICATION) || notificationToSend.source.shouldNCForceSwitchToThisNotification()) ? 0x4 : 0);
        flags |= (byte) (notificationToSend.source.shouldScrollToEnd() ? 0x8 : 0);

        if (amountOfActions > 0 && showMenuInstantly)
        {
            flags |= (byte) ((notificationToSend.source.shouldForceActionMenu() || settingStorage.getInt(AppSetting.SELECT_PRESS_ACTION) == 2) ? 0x10 : 0);
            flags |= (byte) (settingStorage.getInt(AppSetting.SELECT_HOLD_ACTION) == 2 ? 0x20 : 0);
        }

        byte[] configBytes = new byte[18 + vibrationPattern.size()];
        configBytes[0] = flags;
        configBytes[1] = (byte) (periodicVibrationInterval >>> 0x08);
        configBytes[2] = (byte) periodicVibrationInterval;
        configBytes[3] = (byte) amountOfActions;
        configBytes[4] = (byte) (notificationToSend.textLength >>> 0x08);
        configBytes[5] = (byte) notificationToSend.textLength;

        int shakeAction = settingStorage.getInt(AppSetting.SHAKE_ACTION);
        if (shakeAction == 2 && !showMenuInstantly )
            configBytes[6] = 1;
        else
            configBytes[6] = (byte) shakeAction;

        configBytes[7] = (byte) settingStorage.getInt(AppSetting.TITLE_FONT);
        configBytes[8] = (byte) settingStorage.getInt(AppSetting.SUBTITLE_FONT);
        configBytes[9] = (byte) settingStorage.getInt(AppSetting.BOCY_FONT);

        if (getService().getPebbleCommunication().getConnectedWatchCapabilities().hasColorScreen())
        {
            int color = notificationToSend.source.getColor();
            if (color == Color.TRANSPARENT)
                color = Color.BLACK;

            configBytes[10] = PebbleImageToolkit.getGColor8FromRGBColor(color);
        }

        notificationToSend.backgroundImageData = ImageSendingModule.prepareImage(notificationToSend.source.getBigNotificationImage());
        if (notificationToSend.backgroundImageData == null || !getService().getPebbleCommunication().getConnectedWatchCapabilities().hasColorScreen())
        {
            configBytes[11] = 0;
            configBytes[12] = 0;
        }
        else
        {
            int size = notificationToSend.backgroundImageData.length;

            configBytes[11] = (byte) (size >>> 8);
            configBytes[12] = (byte) size;
        }

        configBytes[13] = (byte) (notificationToSend.firstSubtitleIndex >>> 0x08);
        configBytes[14] = (byte) notificationToSend.firstSubtitleIndex;
        configBytes[15] = (byte) (notificationToSend.firstTextIndex >>> 0x08);
        configBytes[16] = (byte) notificationToSend.firstTextIndex;

        configBytes[17] = (byte) vibrationPattern.size();

        for (int i = 0; i < vibrationPattern.size(); i++)
            configBytes[18 + i] = vibrationPattern.get(i);

        data.addUint8(0, (byte) 1);
        data.addUint8(1, (byte) 0);
        data.addInt32(2, notificationToSend.id);
        data.addBytes(3, configBytes);
        data.addInt32(4, notificationToSend.prevId);
        data.addUint8(999, (byte) 1);

        data.addUint16(5, (short) 0); //Placeholder

        int iconSize = 0;
        Bitmap icon = notificationToSend.source.getNotificationIcon();
        if (icon != null)
        {
            PebbleCapabilities watchCapabilities = getService().getPebbleCommunication().getConnectedWatchCapabilities();
            byte[] iconData = ImageSendingModule.prepareIcon(icon, getService(), watchCapabilities);
            notificationToSend.iconData = iconData;
            iconSize = iconData.length;
            notificationToSend.needsIconSending = true;
        }
        data.addUint16(5, (short) iconSize);

        getService().getPebbleCommunication().sendToPebble(data);
    }

    private void sendMoreText()
    {
        Timber.d("Sending more text... %d %d", curSendingNotification.id, curSendingNotification.nextChunkToSend);

        PebbleDictionary data = new PebbleDictionary();
        data.addUint8(0, (byte) 1);
        data.addUint8(1, (byte) 1);
        data.addInt32(2, curSendingNotification.id);
        data.addBytes(3, curSendingNotification.textChunks.get(curSendingNotification.nextChunkToSend));

        getService().getPebbleCommunication().sendToPebble(data);
        curSendingNotification.nextChunkToSend++;
    }

    private boolean sendWatchappIcon()
    {
        Timber.d("Sending icon");

        PebbleDictionary data = new PebbleDictionary();
        data.addUint8(0, (byte) 1);
        data.addUint8(1, (byte) 2);
        data.addInt32(2, curSendingNotification.id);

        curSendingNotification.needsIconSending = false;

        // Only send icon if it can fit into one Appmessage
        if (curSendingNotification.iconData.length <= PebbleUtil.getBytesLeft(data, getService().getPebbleCommunication().getConnectedWatchCapabilities()))
        {
            data.addBytes(3, curSendingNotification.iconData);
            getService().getPebbleCommunication().sendToPebble(data);
            return true;
        }
        else
        {
            Timber.d("Sending failed! Icon cannot fit into AppMessage.");
            return sendNextMessage(); //Icon sending failed, send next message in a row.
        }
    }

    private void onNotificationSendConfirmed(int notificationId)
    {
        ProcessedNotification notification = NCTalkerService.fromPebbleTalkerService(getService()).sentNotifications.get(notificationId);
        if (notification == null)
        {
            Timber.w("Received confirmation for inexistent notification: %d", notificationId);

            //Looks like notification we tried to send got deleted before we could send it further. Lets retry with another.
            if (!sendingQueue.isEmpty())
            {
                resetSendingQueue();
                getService().getPebbleCommunication().queueModulePriority(this);
                getService().getPebbleCommunication().sendNext();
            }

            return;
        }

        sendingQueue.remove(notification);
        curSendingNotification = notification;

        PebbleCommunication communication = getService().getPebbleCommunication();
        communication.queueModulePriority(this);
        communication.sendNext();

        boolean queueImage = notification.backgroundImageData != null && getService().getPebbleCommunication().getConnectedWatchCapabilities().hasColorScreen();
        if (queueImage)
            ImageSendingModule.get(getService()).startSendingImage(notification);

    }

    @Override
    public boolean sendNextMessage()
    {
        if (curSendingNotification == null)
        {
            if (!sendingQueue.isEmpty() && !sendingQueue.peek().waitingForConfirmation)
            {
                sendInitialNotificationPacket();

                return true;
            }

            return false;
        }
        else if (curSendingNotification.nextChunkToSend < 0)
        {
            sendInitialNotificationPacket();
        }
        else if (curSendingNotification.needsIconSending)
        {
            return sendWatchappIcon();
        }
        else if (curSendingNotification.nextChunkToSend < curSendingNotification.textChunks.size())
        {
            sendMoreText();
        }
        else
        {
            notificationTransferCompleted();
            return sendNextMessage();
        }


        return true;
    }

    @Override
    public void gotMessageFromPebble(PebbleDictionary message)
    {
        int id = message.getUnsignedIntegerAsLong(1).intValue();
        switch (id)
        {
            case 0:
                onNotificationSendConfirmed(message.getInteger(2).intValue());
                break;
        }
    }

    @Override
    public void gotIntent(Intent intent)
    {
        if (intent.getAction().equals(INTENT_NOTIFICATION))
        {
            final PebbleNotification notification = processingQueue.poll();
            if (notification == null)
                return;

            // Process summary notifications 500ms later than others to make sure
            // any non-summary notifications can get processed first
            if (notification.getWearGroupType() == PebbleNotification.WEAR_GROUP_TYPE_GROUP_SUMMARY)
            {
                getService().runOnPebbleThreadDelayed(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        processNotification(notification);
                    }
                }, 500);
            }
            else
            {
                processNotification(notification);
            }
        }
        else if (intent.getAction().equals(INTENT_MUTE_APP_TEMPORARILY))
        {
            String appPackage = intent.getStringExtra("AppPackage");
            long until = intent.getLongExtra("MutedUntil", 0);

            temporaryMutes.put(appPackage, until);
        }
        else if (intent.getAction().equals(INTENT_CLEAR_TEMPORARY_MUTES))
        {
            temporaryMutes.clear();
        }
    }

    @Override
    public void pebbleAppOpened() {
        resetSendingQueue();

        if (curSendingNotification != null) {
            sendingQueue.add(curSendingNotification);
            curSendingNotification = null;
        }

        if (!sendingQueue.isEmpty())
        {
            getService().getPebbleCommunication().queueModulePriority(this);
        }
    }

    private List<Byte> getVibrationPattern(ProcessedNotification notification, AppSettingStorage settingStorage)
    {
        Long lastVibration = lastAppVibration.get(notification.source.getKey().getPackage());
        int minInterval = 0;

        try
        {
            minInterval = Integer.parseInt(settingStorage.getString(AppSetting.MINIMUM_VIBRATION_INTERVAL));
        }
        catch (NumberFormatException e)
        {
        }

        Timber.d("MinInterval: %d", minInterval);
        Timber.d("LastVib: %d", lastVibration);

        if (minInterval == 0 || lastVibration == null ||
                (System.currentTimeMillis() - lastVibration) > minInterval * 1000)
        {
            notification.vibrated = true;

            long[] forcedVibrationPattern = notification.source.getForcedVibrationPattern();
            if (forcedVibrationPattern == null)
                return PebbleVibrationPattern.parseVibrationPattern(settingStorage.getString(AppSetting.VIBRATION_PATTERN));
            return PebbleVibrationPattern.getFromAndroidVibrationPattern(forcedVibrationPattern);
        }
        else
        {
            ArrayList<Byte> list = new ArrayList<Byte>(2);
            list.add((byte) 0);
            list.add((byte) 0);
            return list;
        }
    }

    private boolean canDisplayWearGroupNotification(PebbleNotification notification, AppSettingStorage settingStorage)
    {
        boolean groupNotificationEnabled = settingStorage.getBoolean(AppSetting.USE_WEAR_GROUP_NOTIFICATIONS);
        if (notification.getWearGroupType() == PebbleNotification.WEAR_GROUP_TYPE_GROUP_SUMMARY && groupNotificationEnabled)
        {
            //This is summary notification. Only display it if there are no non-summary notifications from the same group already displayed.
            SparseArray<ProcessedNotification> sentNotifications = NCTalkerService.fromPebbleTalkerService(getService()).sentNotifications;
            for (int i = 0; i < sentNotifications.size(); i++)
            {
                PebbleNotification comparing = sentNotifications.valueAt(i).source;
                if (comparing.getWearGroupType() == PebbleNotification.WEAR_GROUP_TYPE_GROUP_MESSAGE && comparing.getKey().getPackage() != null && comparing.getKey().getPackage().equals(notification.getKey().getPackage()) && comparing.getWearGroupKey().equals(notification.getWearGroupKey()))
                {
                    Timber.d("group notify failed - summary with existing non-summary notifications");
                    return false;
                }
            }
        }
        else if (notification.getWearGroupType() == PebbleNotification.WEAR_GROUP_TYPE_GROUP_MESSAGE && !groupNotificationEnabled)
        {
            return false; //Don't send group notifications, they are not enabled.
        }

        boolean sendIdentical = settingStorage.getBoolean(AppSetting.SEND_IDENTICAL_NOTIFICATIONS);
        if (notification.getWearGroupType() == PebbleNotification.WEAR_GROUP_TYPE_GROUP_MESSAGE || !sendIdentical)
        {
            SparseArray<ProcessedNotification> sentNotifications = NCTalkerService.fromPebbleTalkerService(getService()).sentNotifications;

            //Prevent re-sending of the first message.
            for (int i = 0; i < sentNotifications.size(); i++)
            {
                ProcessedNotification comparing = sentNotifications.valueAt(i);
                if ((comparing.source.getWearGroupType() != PebbleNotification.WEAR_GROUP_TYPE_GROUP_SUMMARY || !sendIdentical) && notification.hasIdenticalContent(comparing.source))
                {
                    Timber.d("group notify failed - same notification exists");
                    return false;
                }
            }
        }

        return true;
    }

    public boolean isAnyNotificationWaiting()
    {
        return curSendingNotification != null || !sendingQueue.isEmpty();
    }

    public void removeNotificationFromSendingQueue(int id)
    {
        Iterator<ProcessedNotification> iterator = sendingQueue.iterator();
        while (iterator.hasNext())
        {
            ProcessedNotification notification = iterator.next();

            if (notification.id == id)
            {
                iterator.remove();
            }
        }

        if (curSendingNotification != null && curSendingNotification.id == id)
        {
            notificationTransferCompleted();
        }
    }

    public void clearSendingQueue()
    {
        sendingQueue.clear();
        curSendingNotification = null;
    }

    public void resetSendingQueue()
    {
        for (ProcessedNotification notification : sendingQueue)
        {
            notification.waitingForConfirmation = false;
        }

        if (curSendingNotification != null)
        {
            curSendingNotification.waitingForConfirmation = false;
        }
    }

    public ProcessedNotification getCurrrentSendingNotification()
    {
        return curSendingNotification;
    }

    public static void notify(PebbleNotification notification, Context context)
    {
        processingQueue.add(notification);

        Intent intent = new Intent(context, NCTalkerService.class);
        intent.setAction(INTENT_NOTIFICATION);
        context.startService(intent);
    }

    public static void muteApp(Context context, String appPackage, long until)
    {
        Intent intent = new Intent(context, NCTalkerService.class);
        intent.setAction(INTENT_MUTE_APP_TEMPORARILY);
        intent.putExtra("AppPackage", appPackage);
        intent.putExtra("MutedUntil", until);

        context.startService(intent);
    }

    public static void clearTemporaryMutes(Context context)
    {
        Intent intent = new Intent(context, NCTalkerService.class);
        intent.setAction(INTENT_CLEAR_TEMPORARY_MUTES);

        context.startService(intent);
    }

    public static NotificationSendingModule get(PebbleTalkerService service)
    {
        return (NotificationSendingModule) service.getModule(MODULE_NOTIFICATION_SENDING);
    }

    public static int getMaximumTextLength(AppSettingStorage storage)
    {
        int limit = DEFAULT_TEXT_LIMIT;

        try
        {
            limit = Math.min(Integer.parseInt(storage.getString(AppSetting.MAXIMUM_TEXT_LENGTH)), DEFAULT_TEXT_LIMIT);
            if (limit < 4) //Minimum limit is 4 to allow ...
                limit = 4;
        }
        catch (NumberFormatException e)
        {

        }

        return limit;
    }

    /**
     * Wrapper for PebbleKit.isWatchConnected
     */
    public static boolean isWatchConnected(Context context)
    {
        try {
            return PebbleKit.isWatchConnected(context);
        } catch (Exception e) {
            return false;
        }
    }

    private enum FilteringResult
    {
        SEND,
        ONLY_SAVE_TO_HISTORY,
        ONLY_KEEP_TEMPORARY,
        IGNORE
    }
}
