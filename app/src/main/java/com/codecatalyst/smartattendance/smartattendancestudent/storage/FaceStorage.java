package com.codecatalyst.smartattendance.smartattendancestudent.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class FaceStorage {

    private static final String PREF_NAME = "face_embeddings";
    private static final String KEY_EMBEDDING = "embedding_vector";
    private static final String TAG = "FaceStorage";

    public static void saveEmbedding(Context context, float[] embedding) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * 4);
        buffer.order(ByteOrder.nativeOrder());
        for (float v : embedding) buffer.putFloat(v);

        String base64 = Base64.encodeToString(buffer.array(), Base64.DEFAULT);
        editor.putString(KEY_EMBEDDING, base64);
        editor.apply();

        Log.d(TAG, "✅ Face embedding saved locally (" + embedding.length + " dims)");
    }

    public static float[] loadEmbedding(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String base64 = prefs.getString(KEY_EMBEDDING, null);
        if (base64 == null) return null;

        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        float[] embedding = new float[bytes.length / 4];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = buffer.getFloat();
        }

        Log.d(TAG, "✅ Face embedding loaded (" + embedding.length + " dims)");
        return embedding;
    }

    public static boolean hasEmbedding(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.contains(KEY_EMBEDDING);
    }

    public static void clearEmbedding(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        Log.d(TAG, "🗑️ Face embedding cleared");
    }
}
