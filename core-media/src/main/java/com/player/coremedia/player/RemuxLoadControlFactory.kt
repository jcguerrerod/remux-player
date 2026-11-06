package com.player.coremedia.player

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Motor de Buffer Profundo para Picos de 150+ Mbps (Deep RAM Ring-Buffer).
 *
 * Los discos 4K UHD REMUX presentan una tasa de bits variable (VBR) extrema que supera con frecuencia
 * los 120-160 Mbps en escenas de acción compleja (ej. explosiones en Raiders o escenas espaciales en Hail Mary).
 * El buffer por defecto de ExoPlayer (~50 MB) se agota en menos de 2.5 segundos ante cualquier micro-congestión
 * de red Wi-Fi o en interfaces Ethernet 100M típicas de televisores Android TV.
 *
 * Este motor analiza la memoria RAM física disponible en el dispositivo y despliega un ring-buffer deslizante
 * de hasta 350 MB (~60-90 segundos de pre-almacenamiento en RAM) con bloques de asignación optimizados (128 KB)
 * y retención de back-buffer para reanudación instantánea sin peticiones de red al rebobinar escenas recientes.
 */
object RemuxLoadControlFactory {

    private const val TAG = "RemuxLoadControl"

    data class RemuxBufferProfile(
        val profileName: String,
        val targetBufferBytes: Int,
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
        val backBufferDurationMs: Int,
        val individualAllocationBlockSize: Int,
        val prioritizeTimeOverSize: Boolean
    )

    fun getBufferProfile(context: Context): RemuxBufferProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        val totalMemMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val isLowRam = activityManager?.isLowRamDevice == true || totalMemMb < 2048

        return when {
            // Perfil Ultra-Remux (Shield TV, Sony Bravia XR, TVs con >= 3 GB RAM)
            totalMemMb >= 3000 -> {
                RemuxBufferProfile(
                    profileName = "ULTRA DEEP BUFFER (350 MB)",
                    targetBufferBytes = 350 * 1024 * 1024, // 350 MB en RAM (~30s a 100 Mbps continuos)
                    minBufferMs = 30000,                  // 30 segundos mínimo para suspender descarga
                    maxBufferMs = 90000,                  // 90 segundos de capacidad máxima en RAM
                    bufferForPlaybackMs = 1500,           // 1.5s para arranque instantáneo estilo Apple TV
                    bufferForPlaybackAfterRebufferMs = 3000,
                    backBufferDurationMs = 30000,         // 30s retenidos en RAM para rebobinado instantáneo
                    individualAllocationBlockSize = 131072, // 128 KB por bloque para reducir sobrecarga GC
                    prioritizeTimeOverSize = true
                )
            }
            // Perfil Balanceado (Televisores estándar de 2 GB a 3 GB RAM)
            !isLowRam && totalMemMb in 2048..2999 -> {
                RemuxBufferProfile(
                    profileName = "BALANCED DEEP BUFFER (200 MB)",
                    targetBufferBytes = 200 * 1024 * 1024, // 200 MB en RAM
                    minBufferMs = 20000,                  // 20 segundos
                    maxBufferMs = 60000,                  // 60 segundos
                    bufferForPlaybackMs = 1500,
                    bufferForPlaybackAfterRebufferMs = 2500,
                    backBufferDurationMs = 15000,         // 15s retenidos en RAM
                    individualAllocationBlockSize = 65536,  // 64 KB
                    prioritizeTimeOverSize = true
                )
            }
            // Perfil Seguro para TV Sticks o dispositivos < 2 GB RAM
            else -> {
                RemuxBufferProfile(
                    profileName = "SAFE COMPACT BUFFER (100 MB)",
                    targetBufferBytes = 100 * 1024 * 1024, // 100 MB en RAM
                    minBufferMs = 12000,                  // 12 segundos
                    maxBufferMs = 35000,                  // 35 segundos
                    bufferForPlaybackMs = 1500,
                    bufferForPlaybackAfterRebufferMs = 2500,
                    backBufferDurationMs = 5000,          // 5s retenidos
                    individualAllocationBlockSize = 65536,
                    prioritizeTimeOverSize = true
                )
            }
        }
    }

    fun create(context: Context): LoadControl {
        val profile = getBufferProfile(context)
        Log.i(TAG, "Configurando LoadControl: ${profile.profileName} [Target: ${profile.targetBufferBytes / (1024 * 1024)} MB, Min: ${profile.minBufferMs / 1000}s, Max: ${profile.maxBufferMs / 1000}s, RetainBack: ${profile.backBufferDurationMs / 1000}s]")

        val allocator = DefaultAllocator(true, profile.individualAllocationBlockSize)

        return DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                profile.bufferForPlaybackMs,
                profile.bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(profile.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(profile.prioritizeTimeOverSize)
            .setBackBuffer(profile.backBufferDurationMs, true)
            .build()
    }
}
