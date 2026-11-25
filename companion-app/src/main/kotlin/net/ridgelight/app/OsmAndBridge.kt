package net.ridgelight.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.gpx.ImportGpxParams
import net.osmand.aidlapi.gpx.RemoveGpxParams
import net.osmand.aidlapi.map.SetMapLocationParams
import net.osmand.aidlapi.navdrawer.NavDrawerItem
import net.osmand.aidlapi.navdrawer.SetNavDrawerItemsParams
import net.ridgelight.core.geo.LatLon
import net.ridgelight.core.gpx.OsmAndExport
import kotlin.coroutines.resume

/**
 * Talks to an installed OsmAnd through its AIDL API (the documented way for external plugins).
 * Result tracks are imported as `tracks/ridgelight-*.gpx` in OsmAnd, one per kind of light, and
 * replaced on every new analysis. (Not in a subfolder: OsmAnd 5.4's importGpxFromData opens the
 * file before creating missing parent folders, so a new folder fails with ENOENT.)
 */
class OsmAndBridge(private val context: Context) {
    enum class Outcome { SHOWN, NOT_INSTALLED, NOT_CONNECTED, NOT_ALLOWED }

    private var service: IOsmAndAidlInterface? = null
    private var connection: ServiceConnection? = null
    private var boundPackage: String? = null

    fun installedPackage(): String? = PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private suspend fun connect(): IOsmAndAidlInterface? {
        service?.let { if (it.asBinder().isBinderAlive) return it }
        val pkg = installedPackage() ?: return null
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        val api = IOsmAndAidlInterface.Stub.asInterface(binder)
                        service = api
                        boundPackage = pkg
                        if (cont.isActive) cont.resume(api)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        service = null
                    }
                }
                val intent = Intent(SERVICE_ACTION).setPackage(pkg)
                var flags = Context.BIND_AUTO_CREATE
                if (Build.VERSION.SDK_INT >= 34) flags = flags or Context.BIND_ALLOW_ACTIVITY_STARTS
                val ok = try {
                    context.bindService(intent, conn, flags)
                } catch (e: SecurityException) {
                    Log.w(TAG, "bind refused", e)
                    false
                }
                if (ok) connection = conn else if (cont.isActive) cont.resume(null)
            }
        }
    }

    fun disconnect() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
    }

    /** Where OsmAnd's map should look: centre and OsmAnd zoom level. */
    data class MapView(val center: LatLon, val zoom: Int)

    /** Imports and shows the result files, removes stale ones and moves the map to [view]. */
    suspend fun show(files: List<OsmAndExport.File>, view: MapView): Outcome = withContext(Dispatchers.IO) {
        installedPackage() ?: return@withContext Outcome.NOT_INSTALLED
        val api = connect() ?: return@withContext Outcome.NOT_CONNECTED
        try {
            val present = files.map { it.fileName }.toSet()
            for (light in net.ridgelight.core.analysis.Light.entries) {
                val name = OsmAndExport.fileName(light)
                if (name !in present) runCatching { api.removeGpx(RemoveGpxParams(name)) }
            }
            for (f in files) {
                val ok = api.importGpx(ImportGpxParams(f.gpx, f.fileName, f.color, true))
                if (!ok) return@withContext Outcome.NOT_ALLOWED
            }
            runCatching { api.setMapLocation(view.params()) }
            registerDrawerItem(api)
            Outcome.SHOWN
        } catch (e: RemoteException) {
            Log.w(TAG, "OsmAnd call failed", e)
            service = null
            Outcome.NOT_CONNECTED
        } catch (e: SecurityException) {
            Log.w(TAG, "OsmAnd refused", e)
            Outcome.NOT_ALLOWED
        }
    }

    /**
     * Moves OsmAnd's map again. OsmAnd forwards map moves to its map screen, so a move sent while
     * OsmAnd was not running yet is lost; call this once its map screen is up.
     */
    suspend fun moveMap(view: MapView) = withContext(Dispatchers.IO) {
        runCatching { service?.setMapLocation(view.params()) }
    }

    private fun MapView.params() = SetMapLocationParams(center.lat, center.lon, zoom, Float.NaN, true)

    /** Best effort: a drawer entry in OsmAnd that opens this app. Needs OsmAnd's "System" permission group. */
    private fun registerDrawerItem(api: IOsmAndAidlInterface) {
        runCatching {
            api.setNavDrawerItems(
                SetNavDrawerItemsParams(context.packageName, listOf(NavDrawerItem(context.getString(R.string.app_name), "ridgelight://open", "ic_action_sun"))),
            )
        }
    }

    /**
     * Newer OsmAnd versions grant API access per permission group and offer a request dialog;
     * returns its intent if this OsmAnd has it.
     */
    fun permissionRequestIntent(): Intent? {
        val pkg = installedPackage() ?: return null
        val intent = Intent(PERMISSION_ACTION).setPackage(pkg)
            .putExtra("groups", arrayOf("tracks_edit", "map"))
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    fun launchIntent(): Intent? = installedPackage()?.let { context.packageManager.getLaunchIntentForPackage(it) }

    companion object {
        private const val TAG = "OsmAndBridge"
        const val SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2"
        const val PERMISSION_ACTION = "net.osmand.aidl.REQUEST_PERMISSIONS"
        val PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")
    }
}
