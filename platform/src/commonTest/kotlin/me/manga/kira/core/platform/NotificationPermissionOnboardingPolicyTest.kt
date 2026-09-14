package me.manga.kira.core.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPermissionOnboardingPolicyTest {

    @Test
    fun requiredAutomatic_preservesAndroidOnboardingBehavior() {
        val policy = NotificationPermissionOnboardingPolicy.REQUIRED_AUTOMATIC

        assertTrue(policy.showPermissionControl)
        assertTrue(policy.requestAutomatically)
        assertTrue(policy.requireGrantToContinue)
    }

    @Test
    fun optionalUserInitiated_neverPromptsOrBlocksAutomatically() {
        val policy = NotificationPermissionOnboardingPolicy.OPTIONAL_USER_INITIATED

        assertTrue(policy.showPermissionControl)
        assertFalse(policy.requestAutomatically)
        assertFalse(policy.requireGrantToContinue)
    }

    @Test
    fun notApplicable_omitsThePermissionControl() {
        val policy = NotificationPermissionOnboardingPolicy.NOT_APPLICABLE

        assertFalse(policy.showPermissionControl)
        assertFalse(policy.requestAutomatically)
        assertFalse(policy.requireGrantToContinue)
    }
}
