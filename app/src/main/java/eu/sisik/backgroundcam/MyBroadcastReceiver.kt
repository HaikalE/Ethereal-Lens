package eu.sisik.backgroundcam // Sesuaikan dengan package yang digunakan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class MyBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Contoh aksi yang dilakukan saat menerima broadcast
        Toast.makeText(context, "Broadcast diterima!", Toast.LENGTH_SHORT).show()
    }
}
