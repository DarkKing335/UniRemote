package com.example.uniremote.viewmodel

import android.app.Application
import com.example.uniremote.cast.CastPlaybackInfo
import com.example.uniremote.cast.CastRepository
import com.example.uniremote.cast.CastState
import com.example.uniremote.cast.DefaultCastManager
import com.example.uniremote.data.AppPreferences
import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.data.UserMacro
import com.example.uniremote.domain.AutoConnectUseCase
import com.example.uniremote.domain.ConnectionStatus
import com.example.uniremote.network.DeviceConnectionManager
import com.example.uniremote.network.PairingState
import com.example.uniremote.network.RemoteControlDiscovery
import com.uniremote.dlna.dlna.DlnaRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoteViewModelRecoveryIntegrationTest {

    @Test
    fun `paired google tv retries by re-pairing when reconnect fails`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val application = mock<Application>()
            val prefs = mock<AppPreferences>()
            val repo = mock<DeviceRepository>()
            val discovery = mock<RemoteControlDiscovery>()
            val connectionManager = mock<DeviceConnectionManager>()
            val castRepository = mock<CastRepository>()
            val castManager = mock<DefaultCastManager>()
            val autoConnectUseCase = mock<AutoConnectUseCase>()

            val discovered = TvDevice(
                id = "fpt-1",
                name = "FPT Play 650",
                brand = TvBrand.GOOGLE_TV,
                ip = "192.168.1.2",
                token = null,
                isPaired = false
            )
            val saved = discovered.copy(token = "saved-token", isPaired = true)

            whenever(repo.knownDevices).thenReturn(flowOf(listOf(saved)))
            whenever(prefs.autoReconnect).thenReturn(flowOf(true))
            whenever(prefs.userMacros).thenReturn(flowOf(emptyList<UserMacro>()))
            whenever(discovery.discover()).thenReturn(flowOf(emptyList()))

            whenever(connectionManager.connectionStatus).thenReturn(
                MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
            )
            whenever(connectionManager.connectedDevice).thenReturn(MutableStateFlow<TvDevice?>(null))
            whenever(connectionManager.pairingState).thenReturn(MutableStateFlow(PairingState.IDLE))
            whenever(connectionManager.isAdbFallbackMode).thenReturn(MutableStateFlow(false))
            whenever(connectionManager.connectTo(any())).thenReturn(false, true)
            whenever(connectionManager.startGoogleTvPairing(any())).thenReturn(true)
            whenever(connectionManager.getInstalledApps()).thenReturn(emptyList())

            whenever(castRepository.renderers).thenReturn(MutableStateFlow<List<DlnaRenderer>>(emptyList()))
            whenever(castRepository.castState).thenReturn(MutableStateFlow<CastState>(CastState.Idle))

            whenever(castManager.castRenderers).thenReturn(MutableStateFlow(emptyList()))
            whenever(castManager.castState).thenReturn(MutableStateFlow<CastState>(CastState.Idle))
            whenever(castManager.castPlaybackInfo).thenReturn(MutableStateFlow(CastPlaybackInfo()))
            whenever(castManager.mirrorStreamUrl).thenReturn(MutableStateFlow<String?>(null))
            whenever(castManager.mirrorAuthHint).thenReturn(MutableStateFlow<String?>(null))
            whenever(castManager.mirrorTlsFingerprint).thenReturn(MutableStateFlow<String?>(null))
            whenever(castManager.isMirroring).thenReturn(MutableStateFlow(false))
            whenever(castManager.isCastingActive).thenReturn(MutableStateFlow(false))

            val vm = RemoteViewModel(
                application = application,
                prefs = prefs,
                repo = repo,
                discovery = discovery,
                connectionManager = connectionManager,
                castRepository = castRepository,
                castManager = castManager,
                autoConnectUseCase = autoConnectUseCase,
                initializeOnStartup = false
            )

            vm.connectOrPair(discovered)
            advanceUntilIdle()

            verify(connectionManager, times(2)).connectTo(any())
            verify(connectionManager).startGoogleTvPairing(argThat { id == "fpt-1" && !isPaired })
            verify(repo).markDeviceOnline("fpt-1")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `ip change reconnect preserves credentials and uses latest endpoint`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val application = mock<Application>()
            val prefs = mock<AppPreferences>()
            val repo = mock<DeviceRepository>()
            val discovery = mock<RemoteControlDiscovery>()
            val connectionManager = mock<DeviceConnectionManager>()
            val castRepository = mock<CastRepository>()
            val castManager = mock<DefaultCastManager>()
            val autoConnectUseCase = mock<AutoConnectUseCase>()

            val saved = TvDevice(
                id = "saved-id",
                name = "Living Room TV",
                brand = TvBrand.GOOGLE_TV,
                ip = "192.168.1.20",
                mac = "AA:BB:CC:DD:EE:FF",
                token = "saved-token",
                isPaired = true
            )
            val discovered = TvDevice(
                id = "scan-id",
                name = "Living Room TV",
                brand = TvBrand.GOOGLE_TV,
                ip = "192.168.1.99",
                mac = "AA-BB-CC-DD-EE-FF",
                token = null,
                isPaired = false
            )

            whenever(repo.knownDevices).thenReturn(flowOf(listOf(saved)))
            whenever(prefs.autoReconnect).thenReturn(flowOf(true))
            whenever(prefs.userMacros).thenReturn(flowOf(emptyList<UserMacro>()))
            whenever(discovery.discover()).thenReturn(flowOf(emptyList()))

            whenever(connectionManager.connectionStatus).thenReturn(
                MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
            )
            whenever(connectionManager.connectedDevice).thenReturn(MutableStateFlow<TvDevice?>(null))
            whenever(connectionManager.pairingState).thenReturn(MutableStateFlow(PairingState.IDLE))
            whenever(connectionManager.isAdbFallbackMode).thenReturn(MutableStateFlow(false))
            whenever(connectionManager.connectTo(any())).thenReturn(true)
            whenever(connectionManager.getInstalledApps()).thenReturn(emptyList())

            whenever(castRepository.renderers).thenReturn(MutableStateFlow<List<DlnaRenderer>>(emptyList()))
            whenever(castRepository.castState).thenReturn(MutableStateFlow<CastState>(CastState.Idle))

            whenever(castManager.castRenderers).thenReturn(MutableStateFlow(emptyList()))
            whenever(castManager.castState).thenReturn(MutableStateFlow<CastState>(CastState.Idle))
            whenever(castManager.castPlaybackInfo).thenReturn(MutableStateFlow(CastPlaybackInfo()))
            whenever(castManager.mirrorStreamUrl).thenReturn(MutableStateFlow<String?>(null))
            whenever(castManager.mirrorAuthHint).thenReturn(MutableStateFlow<String?>(null))
            whenever(castManager.mirrorTlsFingerprint).thenReturn(MutableStateFlow<String?>(null))
            whenever(castManager.isMirroring).thenReturn(MutableStateFlow(false))
            whenever(castManager.isCastingActive).thenReturn(MutableStateFlow(false))

            val vm = RemoteViewModel(
                application = application,
                prefs = prefs,
                repo = repo,
                discovery = discovery,
                connectionManager = connectionManager,
                castRepository = castRepository,
                castManager = castManager,
                autoConnectUseCase = autoConnectUseCase,
                initializeOnStartup = false
            )

            vm.connectTo(discovered)
            advanceUntilIdle()

            val connectCaptor = argumentCaptor<TvDevice>()
            verify(connectionManager).connectTo(connectCaptor.capture())
            val effective = connectCaptor.firstValue

            assertEquals("saved-id", effective.id)
            assertEquals("192.168.1.99", effective.ip)
            assertEquals("saved-token", effective.token)
            assertTrue(effective.isPaired)

            verify(repo).markDeviceOnline("saved-id")
            verify(repo).saveDevice(
                argThat {
                    id == "saved-id" &&
                        ip == "192.168.1.99" &&
                        token == "saved-token" &&
                        isPaired
                },
                any()
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `scanDevices finalizes after timeout and marks scan complete`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val application = mock<Application>()
            val prefs = mock<AppPreferences>()
            val repo = mock<DeviceRepository>()
            val discovery = mock<RemoteControlDiscovery>()
            val connectionManager = mock<DeviceConnectionManager>()
            val castRepository = mock<CastRepository>()
            val castManager = mock<DefaultCastManager>()
            val autoConnectUseCase = mock<AutoConnectUseCase>()

            whenever(repo.knownDevices).thenReturn(flowOf(emptyList<TvDevice>()))
            whenever(prefs.autoReconnect).thenReturn(flowOf(true))
            whenever(prefs.userMacros).thenReturn(flowOf(emptyList<UserMacro>()))
            whenever(discovery.discover()).thenReturn(flow { awaitCancellation() })

            whenever(connectionManager.connectionStatus).thenReturn(
                MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
            )
            whenever(connectionManager.connectedDevice).thenReturn(MutableStateFlow<TvDevice?>(null))
            whenever(connectionManager.pairingState).thenReturn(MutableStateFlow(PairingState.IDLE))
            whenever(connectionManager.isAdbFallbackMode).thenReturn(MutableStateFlow(false))

            whenever(castRepository.renderers).thenReturn(MutableStateFlow<List<DlnaRenderer>>(emptyList()))
            whenever(castRepository.castState).thenReturn(MutableStateFlow<CastState>(CastState.Idle))

            whenever(castManager.castRenderers).thenReturn(MutableStateFlow(emptyList()))
            whenever(castManager.castState).thenReturn(MutableStateFlow<CastState>(CastState.Idle))
            whenever(castManager.castPlaybackInfo).thenReturn(MutableStateFlow(CastPlaybackInfo()))
            whenever(castManager.mirrorStreamUrl).thenReturn(MutableStateFlow<String?>(null))
            whenever(castManager.mirrorAuthHint).thenReturn(MutableStateFlow<String?>(null))
            whenever(castManager.mirrorTlsFingerprint).thenReturn(MutableStateFlow<String?>(null))
            whenever(castManager.isMirroring).thenReturn(MutableStateFlow(false))
            whenever(castManager.isCastingActive).thenReturn(MutableStateFlow(false))

            val vm = RemoteViewModel(
                application = application,
                prefs = prefs,
                repo = repo,
                discovery = discovery,
                connectionManager = connectionManager,
                castRepository = castRepository,
                castManager = castManager,
                autoConnectUseCase = autoConnectUseCase,
                initializeOnStartup = false
            )

            vm.scanDevices()
            advanceTimeBy(10_500L)
            advanceUntilIdle()

            verify(repo).updateScanResults(any(), eq(true))
        } finally {
            Dispatchers.resetMain()
        }
    }
}
