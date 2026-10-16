package ch.snapper.android

import ch.snapper.android.features.system.SystemFeed
import ch.snapper.android.features.system.SystemFeedState
import ch.snapper.android.features.system.SystemState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemStateTest {
    @Test fun noPermissionDoesNotExposeAnyFeed() {
        SystemFeed.entries.forEach { assertFalse(it.isAllowed(emptySet())) }
    }

    @Test fun userDirectoryRequiresManageUsersRatherThanRoleName() {
        assertFalse(SystemFeed.USERS.isAllowed(setOf("admin", "read:system_status")))
        assertTrue(SystemFeed.USERS.isAllowed(setOf("manage:users")))
    }

    @Test fun oneFeedFailureDoesNotReplaceAnotherFeedState() {
        val state = SystemState(health = SystemFeedState(error = UiError.NETWORK), strategies = SystemFeedState(value = emptyList()))
        assertTrue(state.strategies.value?.isEmpty() == true)
        assertNull(state.strategies.error)
        assertFalse(state.strategies.isLoading)
    }
}
