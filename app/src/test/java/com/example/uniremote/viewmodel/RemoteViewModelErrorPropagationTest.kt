package com.example.uniremote.viewmodel

import android.app.Application
import com.example.uniremote.cast.CastRepository
import com.example.uniremote.cast.CastState
import com.example.uniremote.data.AppPreferences
import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.data.TvDevice
import com.example.uniremote.data.UserMacro
import com.example.uniremote.domain.AutoConnectUseCase
import com.example.uniremote.domain.ConnectionStatus
import com.example.uniremote.network.DeviceConnectionManager
import com.example.uniremote.network.DeviceDiscovery
import com.example.uniremote.network.PairingState
import com.uniremote.dlna.dlna.DlnaRenderer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class RemoteViewModelErrorPropagationTest {

    @Test
    fun `launchAppInternal emits toast when command execution fails`() = runTest {
        val application = mock<Application>()
        val prefs = mock<AppPreferences>()
        val repo = mock<DeviceRepository>()
        val discovery = mock<DeviceDiscovery>()
        val connectionManager = mock<DeviceConnectionManager>()
        val castRepository = mock<CastRepository>()
        val autoConnectUseCase = mock<AutoConnectUseCase>()

        whenever(repo.knownDevices).thenReturn(flowOf(emptyList<TvDevice>()))
        whenever(prefs.autoReconnect).thenReturn(flowOf(true))
        whenever(prefs.userMacros).thenReturn(flowOf(emptyList<UserMacro>()))

        whenever(connectionManager.connectionStatus).thenReturn(MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected))
        whenever(connectionManager.connectedDevice).thenReturn(MutableStateFlow<TvDevice?>(null))
        whenever(connectionManager.pairingState).thenReturn(MutableStateFlow(PairingState.IDLE))

        whenever(castRepository.renderers).thenReturn(MutableStateFlow<List<DlnaRenderer>>(emptyList()))
        whenever(castRepository.castState).thenReturn(MutableStateFlow<CastState>(CastState.Idle))

        whenever(connectionManager.launchApp(any())).thenThrow(IllegalStateException("No active TV connection"))

        val vm = RemoteViewModel(
            application = application,
            prefs = prefs,
            repo = repo,
            discovery = discovery,
            connectionManager = connectionManager,
            castRepository = castRepository,
            autoConnectUseCase = autoConnectUseCase,
            initializeOnStartup = false
        )

        vm.launchAppInternal("com.test.app")

        val toast = withTimeout(1_000L) { vm.toastMessage.first() }
        assertTrue(toast.contains("No active TV connection"))
    }
}
