package vn.datthu.robot;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public class AssistSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new Session(this);
    }

    static class Session extends VoiceInteractionSession {
        Session(Context c) { super(c); }

        @Override
        public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);
            Intent i = new Intent(getContext(), MainActivity.class);
            i.putExtra("autostart", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startAssistantActivity(i);
            hide();
        }
    }
}
