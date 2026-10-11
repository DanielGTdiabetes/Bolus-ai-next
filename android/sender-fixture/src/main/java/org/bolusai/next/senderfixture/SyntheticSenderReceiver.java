package org.bolusai.next.senderfixture;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Synthetic sender for instrumented tests only: no network, storage or configurable destination. It holds no clinical
 * values. The G7 command (ADR 0022) relays a payload that the test defines, with the observed Dexcom action, to
 * Next only, so the receiver can prove that a sender other than the approved producer is rejected or authenticated
 * only against a synthetic test policy.
 */
public final class SyntheticSenderReceiver extends BroadcastReceiver {
    private static final String NEXT_PACKAGE = "org.bolusai.next";

    @Override
    public void onReceive(Context context, Intent command) {
        Intent response;
        if ("org.bolusai.next.senderfixture.TRIGGER".equals(command.getAction())) {
            response = new Intent("org.bolusai.next.senderfixture.IDENTITY_PROBE")
                    .putExtra("packageName", "example.forged.package");
        } else if ("org.bolusai.next.senderfixture.TRIGGER_G7".equals(command.getAction())) {
            response = new Intent("com.dexcom.cgm.EXTERNAL_BROADCAST");
            Bundle payload = command.getBundleExtra("payload");
            if (payload != null) response.putExtras(payload);
        } else {
            return;
        }
        response.setPackage(NEXT_PACKAGE);
        if (command.getBooleanExtra("shareIdentity", false)) {
            BroadcastOptions options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true);
            context.sendBroadcast(response, null, options.toBundle());
        } else {
            context.sendBroadcast(response);
        }
    }
}
