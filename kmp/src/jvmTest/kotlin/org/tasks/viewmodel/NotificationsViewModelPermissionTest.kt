package org.tasks.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.tasks.PlatformConfiguration
import org.tasks.notifications.NotificationPermission
import org.tasks.preferences.AppPreferences

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelPermissionTest {

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(permission: () -> NotificationPermission) = NotificationsViewModel(
        appPreferences = mock<AppPreferences>(),
        platformConfiguration = PlatformConfiguration(),
        persistenceScope = CoroutineScope(Dispatchers.Unconfined),
        rescheduleNotifications = {},
        notificationPermission = { permission() },
    )

    @Test
    fun deniedPermissionIsReported() {
        val vm = viewModel { NotificationPermission.DENIED }
        vm.refreshPermission()
        assertTrue(vm.notificationsDenied)
    }

    @Test
    fun notYetAskedIsNotReportedAsDenied() {
        val vm = viewModel { NotificationPermission.NOT_DETERMINED }
        vm.refreshPermission()
        assertFalse(vm.notificationsDenied)
    }

    @Test
    fun turningPermissionBackOnClearsTheWarning() {
        var permission = NotificationPermission.DENIED
        val vm = viewModel { permission }
        vm.refreshPermission()
        permission = NotificationPermission.GRANTED
        vm.refreshPermission()
        assertFalse(vm.notificationsDenied)
    }
}
