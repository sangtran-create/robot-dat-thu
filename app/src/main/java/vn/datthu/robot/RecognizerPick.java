package vn.datthu.robot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.speech.RecognitionService;

import java.util.List;

/** Tìm dịch vụ nhận giọng của Google (không phải của chính app này). */
public class RecognizerPick {
    public static ComponentName find(Context ctx) {
        List<ResolveInfo> list = ctx.getPackageManager()
                .queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        ComponentName best = null;
        for (ResolveInfo ri : list) {
            ServiceInfo si = ri.serviceInfo;
            if (si == null || si.packageName.equals(ctx.getPackageName())) continue;
            ComponentName cn = new ComponentName(si.packageName, si.name);
            if ("com.google.android.googlequicksearchbox".equals(si.packageName)) return cn;
            if (best == null) best = cn;
        }
        return best;
    }
}
