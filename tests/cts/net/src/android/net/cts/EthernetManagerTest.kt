/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.net.cts

import android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS
import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.Manifest.permission.NETWORK_SETTINGS
import android.content.Context
import android.net.InetAddresses
import android.net.IpConfiguration
import android.net.MacAddress
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.net.cts.EthernetManagerTest.EthernetStateListener.CallbackEntry.InterfaceStateChanged
import android.os.Handler
import android.os.HandlerExecutor
import android.os.Looper
import android.platform.test.annotations.AppModeFull
import android.util.ArraySet
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.net.module.util.ArrayTrackRecord
import com.android.net.module.util.TrackRecord
import com.android.networkstack.apishim.EthernetManagerShimImpl
import com.android.networkstack.apishim.common.EthernetManagerShim.InterfaceStateListener
import com.android.networkstack.apishim.common.EthernetManagerShim.ROLE_CLIENT
import com.android.networkstack.apishim.common.EthernetManagerShim.ROLE_NONE
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_ABSENT
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_LINK_DOWN
import com.android.networkstack.apishim.common.EthernetManagerShim.STATE_LINK_UP
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.RouterAdvertisementResponder
import com.android.testutils.SC_V2
import com.android.testutils.TapPacketReader
import com.android.testutils.runAsShell
import com.android.testutils.waitForIdle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet6Address
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private const val TIMEOUT_MS = 1000L
private const val NO_CALLBACK_TIMEOUT_MS = 200L
private val DEFAULT_IP_CONFIGURATION = IpConfiguration(IpConfiguration.IpAssignment.DHCP,
    IpConfiguration.ProxySettings.NONE, null, null)

@AppModeFull(reason = "Instant apps can't access EthernetManager")
@RunWith(AndroidJUnit4::class)
class EthernetManagerTest {
    // EthernetManager is not updatable before T, so tests do not need to be backwards compatible
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule(ignoreClassUpTo = SC_V2)

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val em by lazy { EthernetManagerShimImpl.newInstance(context) }

    private val ifaceListener = EthernetStateListener()
    private val createdIfaces = ArrayList<EthernetTestInterface>()
    private val addedListeners = ArrayList<EthernetStateListener>()

    private class EthernetTestInterface(
        context: Context,
        private val handler: Handler
    ) {
        private val tapInterface: TestNetworkInterface
        private val packetReader: TapPacketReader
        private val raResponder: RouterAdvertisementResponder
        val interfaceName get() = tapInterface.interfaceName

        init {
            tapInterface = runAsShell(MANAGE_TEST_NETWORKS) {
                val tnm = context.getSystemService(TestNetworkManager::class.java)
                tnm.createTapInterface(false /* bringUp */)
            }
            val mtu = 1500
            packetReader = TapPacketReader(handler, tapInterface.fileDescriptor.fileDescriptor, mtu)
            raResponder = RouterAdvertisementResponder(packetReader)
            raResponder.addRouterEntry(MacAddress.fromString("01:23:45:67:89:ab"),
                    InetAddresses.parseNumericAddress("fe80::abcd") as Inet6Address)

            packetReader.startAsyncForTest()
            raResponder.start()
        }

        fun destroy() {
            raResponder.stop()
            handler.post({ packetReader.stop() })
            handler.waitForIdle(TIMEOUT_MS)
        }
    }

    private open class EthernetStateListener private constructor(
        private val history: ArrayTrackRecord<CallbackEntry>
    ) : InterfaceStateListener,
                TrackRecord<EthernetStateListener.CallbackEntry> by history {
        constructor() : this(ArrayTrackRecord())

        val events = history.newReadHead()

        sealed class CallbackEntry {
            data class InterfaceStateChanged(
                val iface: String,
                val state: Int,
                val role: Int,
                val configuration: IpConfiguration?
            ) : CallbackEntry()
        }

        override fun onInterfaceStateChanged(
            iface: String,
            state: Int,
            role: Int,
            cfg: IpConfiguration?
        ) {
            add(InterfaceStateChanged(iface, state, role, cfg))
        }

        fun <T : CallbackEntry> expectCallback(expected: T): T {
            val event = pollForNextCallback()
            assertEquals(expected, event)
            return event as T
        }

        fun expectCallback(iface: EthernetTestInterface, state: Int, role: Int) {
            expectCallback(createChangeEvent(iface, state, role))
        }

        fun createChangeEvent(iface: EthernetTestInterface, state: Int, role: Int) =
                InterfaceStateChanged(iface.interfaceName, state, role,
                        if (state != STATE_ABSENT) DEFAULT_IP_CONFIGURATION else null)

        fun pollForNextCallback(): CallbackEntry {
            return events.poll(TIMEOUT_MS) ?: fail("Did not receive callback after ${TIMEOUT_MS}ms")
        }

        fun eventuallyExpect(expected: CallbackEntry) = events.poll(TIMEOUT_MS) { it == expected }

        fun eventuallyExpect(iface: EthernetTestInterface, state: Int, role: Int) {
            assertNotNull(eventuallyExpect(createChangeEvent(iface, state, role)))
        }

        fun assertNoCallback() {
            val cb = events.poll(NO_CALLBACK_TIMEOUT_MS)
            assertNull(cb, "Expected no callback but got $cb")
        }
    }

    @Before
    fun setUp() {
        setIncludeTestInterfaces(true)
        addInterfaceStateListener(ifaceListener)
    }

    @After
    fun tearDown() {
        setIncludeTestInterfaces(false)
        for (iface in createdIfaces) {
            iface.destroy()
        }
        for (listener in addedListeners) {
            em.removeInterfaceStateListener(listener)
        }
    }

    private fun addInterfaceStateListener(listener: EthernetStateListener) {
        runAsShell(CONNECTIVITY_USE_RESTRICTED_NETWORKS) {
            em.addInterfaceStateListener(HandlerExecutor(Handler(Looper.getMainLooper())), listener)
        }
        addedListeners.add(listener)
    }

    private fun createInterface(): EthernetTestInterface {
        val iface = EthernetTestInterface(
            context,
            Handler(Looper.getMainLooper())
        ).also { createdIfaces.add(it) }
        with(ifaceListener) {
            // when an interface comes up, we should always see a down cb before an up cb.
            eventuallyExpect(iface, STATE_LINK_DOWN, ROLE_CLIENT)
            expectCallback(iface, STATE_LINK_UP, ROLE_CLIENT)
        }
        return iface
    }

    private fun setIncludeTestInterfaces(value: Boolean) {
        runAsShell(NETWORK_SETTINGS) {
            em.setIncludeTestInterfaces(value)
        }
    }

    private fun removeInterface(iface: EthernetTestInterface) {
        iface.destroy()
        createdIfaces.remove(iface)
        ifaceListener.eventuallyExpect(iface, STATE_ABSENT, ROLE_NONE)
    }

    @Test
    fun testCallbacks() {
        // If an interface exists when the callback is registered, it is reported on registration.
        val iface = createInterface()
        val listener1 = EthernetStateListener()
        addInterfaceStateListener(listener1)
        validateListenerOnRegistration(listener1)

        // If an interface appears, existing callbacks see it.
        // TODO: fix the up/up/down/up callbacks and only send down/up.
        val iface2 = createInterface()
        listener1.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)
        listener1.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)
        listener1.expectCallback(iface2, STATE_LINK_DOWN, ROLE_CLIENT)
        listener1.expectCallback(iface2, STATE_LINK_UP, ROLE_CLIENT)

        // Register a new listener, it should see state of all existing interfaces immediately.
        val listener2 = EthernetStateListener()
        addInterfaceStateListener(listener2)
        validateListenerOnRegistration(listener2)

        // Removing interfaces first sends link down, then STATE_ABSENT/ROLE_NONE.
        removeInterface(iface)
        for (listener in listOf(listener1, listener2)) {
            listener.expectCallback(iface, STATE_LINK_DOWN, ROLE_CLIENT)
            listener.expectCallback(iface, STATE_ABSENT, ROLE_NONE)
        }

        removeInterface(iface2)
        for (listener in listOf(listener1, listener2)) {
            listener.expectCallback(iface2, STATE_LINK_DOWN, ROLE_CLIENT)
            listener.expectCallback(iface2, STATE_ABSENT, ROLE_NONE)
            listener.assertNoCallback()
        }
    }

    /**
     * Validate all interfaces are returned for an EthernetStateListener upon registration.
     */
    private fun validateListenerOnRegistration(listener: EthernetStateListener) {
        // Get all tracked interfaces to validate on listener registration. Ordering and interface
        // state (up/down) can't be validated for interfaces not created as part of testing.
        val ifaces = em.getInterfaceList()
        val polledIfaces = ArraySet<String>()
        for (i in ifaces) {
            val event = (listener.pollForNextCallback() as InterfaceStateChanged)
            val iface = event.iface
            assertTrue(polledIfaces.add(iface), "Duplicate interface $iface returned")
            assertTrue(ifaces.contains(iface), "Untracked interface $iface returned")
            // If the event's iface was created in the test, additional criteria can be validated.
            createdIfaces.find { it.interfaceName.equals(iface) }?.let {
                assertEquals(event, listener.createChangeEvent(it, STATE_LINK_UP, ROLE_CLIENT))
            }
        }
        // Assert all callbacks are accounted for.
        listener.assertNoCallback()
    }

    @Test
    fun testGetInterfaceList() {
        setIncludeTestInterfaces(true)

        // Create two test interfaces and check the return list contains the interface names.
        val iface1 = createInterface()
        val iface2 = createInterface()
        var ifaces = em.getInterfaceList()
        assertTrue(ifaces.size > 0)
        assertTrue(ifaces.contains(iface1.interfaceName))
        assertTrue(ifaces.contains(iface2.interfaceName))

        // Remove one existing test interface and check the return list doesn't contain the
        // removed interface name.
        removeInterface(iface1)
        ifaces = em.getInterfaceList()
        assertFalse(ifaces.contains(iface1.interfaceName))
        assertTrue(ifaces.contains(iface2.interfaceName))

        removeInterface(iface2)
    }
}
