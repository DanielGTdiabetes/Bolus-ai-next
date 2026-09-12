package org.bolusai.next.senderfixture;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** No clinical fields, real Dexcom actions, network, storage or configurable destinations. */
public final class SyntheticSenderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent command) {
        if (!"org.bolusai.next.senderfixture.TRIGGER".equals(command.getAction())) return;
        Intent response = new Intent("org.bolusai.next.senderfixture.IDENTITY_PROBE")
                .setPackage("org.bolusai.next")
                .putExtra("packageName", "example.forged.package");
        if (command.getBooleanExtra("shareIdentity", false)) {
            BroadcastOptions options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true);
            context.sendBroadcast(response, null, options.toBundle());
        } else {
            context.sendBroadcast(response);
        }
    }
}
