package vn.datthu.robot;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * Khi app là trợ lý mặc định, hệ thống dùng dịch vụ nhận giọng của app.
 * Dịch vụ này chuyển tiếp sang Google để các app khác vẫn nhận giọng bình thường.
 */
public class ProxyRecognitionService extends RecognitionService {
    private SpeechRecognizer inner;

    @Override
    protected void onStartListening(Intent intent, Callback cb) {
        ComponentName cn = RecognizerPick.find(this);
        if (cn == null) {
            try { cb.error(SpeechRecognizer.ERROR_CLIENT); } catch (RemoteException ignored) { }
            return;
        }
        if (inner != null) inner.destroy();
        inner = SpeechRecognizer.createSpeechRecognizer(this, cn);
        inner.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { try { cb.readyForSpeech(b); } catch (RemoteException ignored) { } }
            @Override public void onBeginningOfSpeech() { try { cb.beginningOfSpeech(); } catch (RemoteException ignored) { } }
            @Override public void onRmsChanged(float v) { try { cb.rmsChanged(v); } catch (RemoteException ignored) { } }
            @Override public void onBufferReceived(byte[] buf) { try { cb.bufferReceived(buf); } catch (RemoteException ignored) { } }
            @Override public void onEndOfSpeech() { try { cb.endOfSpeech(); } catch (RemoteException ignored) { } }
            @Override public void onError(int e) { try { cb.error(e); } catch (RemoteException ignored) { } }
            @Override public void onResults(Bundle b) { try { cb.results(b); } catch (RemoteException ignored) { } }
            @Override public void onPartialResults(Bundle b) { try { cb.partialResults(b); } catch (RemoteException ignored) { } }
            @Override public void onEvent(int t, Bundle b) { }
        });
        inner.startListening(intent);
    }

    @Override
    protected void onCancel(Callback cb) {
        if (inner != null) inner.cancel();
    }

    @Override
    protected void onStopListening(Callback cb) {
        if (inner != null) inner.stopListening();
    }

    @Override
    public void onDestroy() {
        if (inner != null) inner.destroy();
        inner = null;
        super.onDestroy();
    }
}
