package com.example.uniremote.domain

import com.example.uniremote.data.DeviceRepository
import com.example.uniremote.data.TvBrand
import com.example.uniremote.data.TvDevice
import com.example.uniremote.network.DeviceConnectionManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for AutoConnectUseCase.
 * Uses Mockito-Kotlin to stub DeviceRepository and DeviceConnectionManager.
 */
class AutoConnectUseCaseTest {

    private val repo = mock<DeviceRepository>()
    private val connManager = mock<DeviceConnectionManager>()
    private val useCase = AutoConnectUseCase(repo, connManager)

    private fun makeDevice(id: String) = TvDevice(
        id = id, name = "TV $id", brand = TvBrand.SAMSUNG,
        ip = "192.168.1.${id.last()}", token = null
    )

    @Test
    fun `returns false when no candidates`() = runTest {
        whenever(repo.getAutoConnectCandidates()).thenReturn(emptyList())
        val result = useCase()
        assertFalse(result)
        verify(connManager, never()).tryConnectSilently(any())
    }

    @Test
    fun `returns true when first device connects on first try`() = runTest {
        val device = makeDevice("1")
        whenever(repo.getAutoConnectCandidates()).thenReturn(listOf(device))
        whenever(connManager.tryConnectSilently(device)).thenReturn(true)

        val result = useCase()
        assertTrue(result)
        verify(connManager, times(1)).tryConnectSilently(device)
        verify(repo, never()).markDeviceOffline(any())
    }

    @Test
    fun `returns true when first device connects on second retry`() = runTest {
        val device = makeDevice("1")
        whenever(repo.getAutoConnectCandidates()).thenReturn(listOf(device))
        whenever(connManager.tryConnectSilently(device))
            .thenReturn(false)   // first attempt fails
            .thenReturn(true)    // retry succeeds

        val result = useCase()
        assertTrue(result)
        verify(connManager, times(2)).tryConnectSilently(device)
        verify(repo, never()).markDeviceOffline(any())
    }

    @Test
    fun `marks device offline when both attempts fail`() = runTest {
        val device = makeDevice("1")
        whenever(repo.getAutoConnectCandidates()).thenReturn(listOf(device))
        whenever(connManager.tryConnectSilently(device)).thenReturn(false)

        useCase()
        verify(repo, times(1)).markDeviceOffline("1")
    }

    @Test
    fun `tries second device after first fails both attempts`() = runTest {
        val d1 = makeDevice("1")
        val d2 = makeDevice("2")
        whenever(repo.getAutoConnectCandidates()).thenReturn(listOf(d1, d2))
        whenever(connManager.tryConnectSilently(d1)).thenReturn(false)
        whenever(connManager.tryConnectSilently(d2)).thenReturn(true)

        val result = useCase()
        assertTrue(result)
        verify(connManager, times(2)).tryConnectSilently(d1)  // both attempts on d1
        verify(connManager, times(1)).tryConnectSilently(d2)  // first attempt on d2 succeeds
    }

    @Test
    fun `returns false and sets disconnected when all candidates fail`() = runTest {
        val d1 = makeDevice("1")
        val d2 = makeDevice("2")
        whenever(repo.getAutoConnectCandidates()).thenReturn(listOf(d1, d2))
        whenever(connManager.tryConnectSilently(any())).thenReturn(false)

        val result = useCase()
        assertFalse(result)
        verify(repo).markDeviceOffline("1")
        verify(repo).markDeviceOffline("2")
        verify(connManager).setDisconnected()
    }
}
