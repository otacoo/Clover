package org.otacoo.chan.core.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.otacoo.chan.ui.service.SavingNotification;

import de.greenrobot.event.EventBus;

public class SaveCancelReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        EventBus.getDefault().post(new SavingNotification.SavingCancelRequestMessage());
    }
}
