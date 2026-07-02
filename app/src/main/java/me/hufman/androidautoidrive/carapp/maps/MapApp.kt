package me.hufman.androidautoidrive.carapp.maps

import android.os.Handler
import android.util.Log
import de.bmw.idrive.BMWRemoting
import de.bmw.idrive.BMWRemotingServer
import de.bmw.idrive.BaseBMWRemotingClient
import io.bimmergestalt.idriveconnectkit.IDriveConnection
import io.bimmergestalt.idriveconnectkit.RHMIUtils.rhmi_setResourceCached
import io.bimmergestalt.idriveconnectkit.android.CarAppResources
import io.bimmergestalt.idriveconnectkit.android.IDriveConnectionStatus
import io.bimmergestalt.idriveconnectkit.android.security.SecurityAccess
import io.bimmergestalt.idriveconnectkit.rhmi.*
import io.bimmergestalt.idriveconnectkit.rhmi.deserialization.loadFromXML
import me.hufman.androidautoidrive.carapp.FullImageInteraction
import me.hufman.androidautoidrive.carapp.FullImageView
import me.hufman.androidautoidrive.carapp.InputState
import me.hufman.androidautoidrive.carapp.RHMIActionAbort
import me.hufman.androidautoidrive.carapp.maps.views.MenuView
import me.hufman.androidautoidrive.carapp.maps.views.PlaceSearchView
import me.hufman.androidautoidrive.carapp.maps.views.SearchResultsView
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.MapPlaceSearch
import me.hufman.androidautoidrive.maps.MapResult
import me.hufman.androidautoidrive.utils.removeFirst
import java.util.*
import kotlin.collections.ArrayList

const val TAG = "MapView"

class MapApp(iDriveConnectionStatus: IDriveConnectionStatus, securityAccess: SecurityAccess, val carAppAssets: CarAppResources,
             val mapAppMode: MapAppMode, val locationProvider: CarLocationProvider,
             val interaction: MapInteractionController, val mapPlaceSearch: MapPlaceSearch, val map: VirtualDisplayScreenCapture) {

	val carappListener = CarAppListener()
	val carConnection: BMWRemotingServer
	val carApp: RHMIApplication
	var searchResults = ArrayList<MapResult>()
	var selectedResult: MapResult? = null

	val menuView: MenuView
	val fullImageView: FullImageView
	val stateInput: RHMIState.PlainState
	val stateInputState: InputState<MapResult>
	val searchResultsView: SearchResultsView

	// map state
	var frameUpdater = FrameUpdater(map, object: FrameModeListener {
		override fun onResume() { interaction.showMap()	}
		override fun onPause() { interaction.pauseMap() }
	})

	init {
		carConnection = IDriveConnection.getEtchConnection(iDriveConnectionStatus.host ?: "127.0.0.1", iDriveConnectionStatus.port ?: 8003, carappListener)
		val appCert = carAppAssets.getAppCertificate(iDriveConnectionStatus.brand ?: "")?.readBytes() as ByteArray
		val sas_challenge = carConnection.sas_certificate(appCert)
		val sas_login = securityAccess.signChallenge(challenge=sas_challenge)
		carConnection.sas_login(sas_login)
		carappListener.server = carConnection

		// create the app in the car
		val rhmiHandle = carConnection.rhmi_create(null, BMWRemoting.RHMIMetaData("me.hufman.androidautoidrive.mapview", BMWRemoting.VersionInfo(0, 1, 0), "me.hufman.androidautoidrive.mapview", "me.hufman"))
		carConnection.rhmi_setResourceCached(rhmiHandle, BMWRemoting.RHMIResourceType.DESCRIPTION, carAppAssets.getUiDescription())
//		RHMIUtils.rhmi_setResourceCached(carConnection, rhmiHandle, BMWRemoting.RHMIResourceType.TEXTDB, carAppAssets.getTextsDB("common"))
		carConnection.rhmi_setResourceCached(rhmiHandle, BMWRemoting.RHMIResourceType.IMAGEDB, carAppAssets.getImagesDB("common"))
		carConnection.rhmi_initialize(rhmiHandle)

		carApp = RHMIApplicationSynchronized(RHMIApplicationIdempotent(RHMIApplicationEtch(carConnection, rhmiHandle)), carConnection)
		carappListener.app = carApp
		carApp.loadFromXML(carAppAssets.getUiDescription()?.readBytes() as ByteArray)

		// figure out the components to use
		Log.i(TAG, "Locating components to use")
		val unclaimedStates = LinkedList(carApp.states.values)
		menuView = MenuView(unclaimedStates.removeFirst { MenuView.fits(it) }, interaction, mapPlaceSearch, frameUpdater, mapAppMode)
		fullImageView = FullImageView(unclaimedStates.removeFirst { FullImageView.fits(it) }, "Map", mapAppMode, object : FullImageInteraction {
			override fun navigateUp() {
				interaction.zoomIn(1)
			}
			override fun navigateDown() {
				interaction.zoomOut(1)
			}
			override fun click() {
			}
			override fun getClickState(): RHMIState {
				return menuView.state
			}
		}, frameUpdater)

		stateInput = carApp.states.values.filterIsInstance<RHMIState.PlainState>().first { state ->
			state.componentsList.filterIsInstance<RHMIComponent.Input>().any { it.suggestAction > 0 }
		}
		stateInputState = PlaceSearchView(stateInput, mapPlaceSearch, interaction)
		searchResultsView = SearchResultsView(unclaimedStates.removeFirst { SearchResultsView.fits(it) }, mapPlaceSearch, interaction, mapAppMode, locationProvider)

		// connect buttons together
		carApp.components.values.filterIsInstance<RHMIComponent.EntryButton>().forEach{
			it.getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.value = menuView.state.id
			Log.i(TAG, "Registering entry button ${it.id} model ${it.getAction()?.asHMIAction()?.getTargetModel()?.asRaIntModel()?.id} to point to main state ${menuView.state.id}")
		}

		// set up the components
		Log.i(TAG, "Setting up component behaviors")
		menuView.initWidgets(fullImageView.state, stateInput)
		fullImageView.initWidgets()
		stateInputState.initWidgets(fullImageView, searchResultsView)
		searchResultsView.initWidgets(fullImageView)

		// register for events from the car
		carConnection.rhmi_addActionEventHandler(rhmiHandle, "me.hufman.androidautoidrive.mapview", -1)
		carConnection.rhmi_addHmiEventHandler(rhmiHandle, "me.hufman.androidautoidrive.mapview", -1, -1)

		// EKSPERYMENT native panel: pokaz nieuzywane komponenty stanu mapy (image 134 + 3x label)
		// rownolegle z dzisiejszym panelem w bitmapie; patrz NativePanelTest
		if (NativePanelTest.NATIVE_PANEL_TEST) {
			initNativePanelTest()
		}
	}

	/** Znajduje ukryte komponenty stanu pelnoekranowej mapy i ustawia je jako testowy natywny panel */
	private fun initNativePanelTest() {
		// stan 19: image(132 mapa), list(133 scroll), image(134 wolny), label(135,136,137 wolne)
		val extraImage = fullImageView.state.componentsList.filterIsInstance<RHMIComponent.Image>().drop(1).firstOrNull()
		val labels = fullImageView.state.componentsList.filterIsInstance<RHMIComponent.Label>()
		// NAKLADKA POROWNAWCZA (nie stan docelowy): natywne komponenty pozycjonowane w lewym
		// pasie DOKLADNIE tam, gdzie docelowo ma byc panel - naloza sie na dzisiejszy panel
		// z bitmapy, zeby ocenic font/pozycje/ucinanie natywnych w ich prawdziwym miejscu.
		// Docelowo panel z bitmapy zniknie (przyciecie capture), a native zajmie jego miejsce.
		Log.i(TAG, "NativePanelTest: NAKLADKA porownawcza w lewym pasie; extraImage=${extraImage?.id} labels=${labels.map { it.id }}")
		if (extraImage == null && labels.isEmpty()) {
			Log.w(TAG, "NativePanelTest: stan mapy nie ma wolnych komponentow - eksperyment niemozliwy")
			return
		}

		// uklad docelowego panelu: ikona manewru u gory, pod nia instrukcja, ETA, dystans
		extraImage?.setProperty(RHMIProperty.PropertyId.POSITION_X.id, 10)
		extraImage?.setProperty(RHMIProperty.PropertyId.POSITION_Y.id, 10)
		extraImage?.setProperty(RHMIProperty.PropertyId.WIDTH.id, 120)
		extraImage?.setProperty(RHMIProperty.PropertyId.HEIGHT.id, 120)
		extraImage?.setVisible(true)
		labels.forEachIndexed { i, label ->
			label.setProperty(RHMIProperty.PropertyId.POSITION_X.id, 10)
			label.setProperty(RHMIProperty.PropertyId.POSITION_Y.id, 150 + i * 60)
			label.setEnabled(true)
			label.setVisible(true)
		}
	}

	fun onCreate(handler: Handler) {
		Log.i(TAG, "Setting up map transfer")
		frameUpdater.start(handler)

		// EKSPERYMENT native panel: zapisy modeli RHMI na watku car (male setData ~1/s)
		if (NativePanelTest.NATIVE_PANEL_TEST) {
			val labels = fullImageView.state.componentsList.filterIsInstance<RHMIComponent.Label>()
			val extraImage = fullImageView.state.componentsList.filterIsInstance<RHMIComponent.Image>().drop(1).firstOrNull()
			NativePanelTest.attach(handler) { iconPng, line1, line2, line3 ->
				if (iconPng != null) {
					(extraImage?.getModel() as? RHMIModel.RaImageModel)?.value = iconPng
				}
				labels.getOrNull(0)?.getModel()?.asRaDataModel()?.value = line1
				labels.getOrNull(1)?.getModel()?.asRaDataModel()?.value = line2
				labels.getOrNull(2)?.getModel()?.asRaDataModel()?.value = line3
			}
		}
	}
	fun onDestroy() {
		if (NativePanelTest.NATIVE_PANEL_TEST) {
			NativePanelTest.detach()
		}
		frameUpdater.shutDown()
		mapAppMode.appSettings.callback = null
	}
	fun disconnect() {
		try {
			IDriveConnection.disconnectEtchConnection(carConnection)
		} catch (e: java.lang.Exception) {}
	}

	inner class CarAppListener: BaseBMWRemotingClient() {
		var server: BMWRemotingServer? = null
		var app: RHMIApplication? = null
		override fun rhmi_onActionEvent(handle: Int?, ident: String?, actionId: Int?, args: MutableMap<*, *>?) {
			Log.w(TAG, "Received rhmi_onActionEvent: handle=$handle ident=$ident actionId=$actionId args=$args")
			try {
				app?.actions?.get(actionId)?.asRAAction()?.rhmiActionCallback?.onActionEvent(args)
				synchronized(server!!) {
					server?.rhmi_ackActionEvent(handle, actionId, 1, true)
				}
			} catch (e: RHMIActionAbort) {
				// Action handler requested that we don't claim success
				synchronized(server!!) {
					server?.rhmi_ackActionEvent(handle, actionId, 1, false)
				}
			} catch (e: Exception) {
				Log.e(me.hufman.androidautoidrive.carapp.notifications.TAG, "Exception while calling onActionEvent handler!", e)
				synchronized(server!!) {
					server?.rhmi_ackActionEvent(handle, actionId, 1, true)
				}
			}
		}

		override fun rhmi_onHmiEvent(handle: Int?, ident: String?, componentId: Int?, eventId: Int?, args: MutableMap<*, *>?) {
			val msg = "Received rhmi_onHmiEvent: handle=$handle ident=$ident componentId=$componentId eventId=$eventId args=${args?.toString()}"
			Log.w(TAG, msg)

			// generic event handler
			app?.states?.get(componentId)?.onHmiEvent(eventId, args)
			app?.components?.get(componentId)?.onHmiEvent(eventId, args)
		}
	}
}
