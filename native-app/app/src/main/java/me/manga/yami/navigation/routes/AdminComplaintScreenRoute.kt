package me.manga.yamiapk.navigation.routes


import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import me.manga.yamiapk.admin.complaint.AdminComplaintScreen
import me.manga.yamiapk.admin.complaint.AdminComplaintViewModel
import me.manga.yamiapk.navigation.safePopBackStack
import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintStatus

@Composable
fun AdminComplaintScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    adminComplaintViewModel: AdminComplaintViewModel = hiltViewModel()
) {
    val coroutine = rememberCoroutineScope()

    // Load complaints when the screen is first opened
    LaunchedEffect(Unit) {
        adminComplaintViewModel.loadAllComplaints()
    }

    val complaintsState by adminComplaintViewModel.complaints.collectAsStateWithLifecycle()
    val isLoading by adminComplaintViewModel.isLoading.collectAsStateWithLifecycle()

    // Message state for showing success/error messages
    val showMessage = remember { { message: String ->
        // You can implement your own snackbar/toast mechanism here
        // For now, we'll just log the message
        println("Admin Message: $message")
    } }

    AdminComplaintScreen(
        complaintsState = complaintsState,
        onRetry = {
            adminComplaintViewModel.loadAllComplaints()
        },
        onBackClick = {
            navController.safePopBackStack()
        },
        onUpdateComplaintStatus = { complaint: Complaint, newStatus: ComplaintStatus ->
            coroutine.launch {
                adminComplaintViewModel.updateComplaintStatus(
                    complaint = complaint,
                    newStatus = newStatus,
                    onSuccess = {
                        showMessage("Status updated to ${newStatus.name}")
                    },
                    onError = { error ->
                        showMessage("Failed to update status: ${error.message}")
                    }
                )
            }
        },
        onDeleteComplaint = { complaint: Complaint ->
            coroutine.launch {
                adminComplaintViewModel.deleteComplaint(
                    complaint = complaint,
                    onSuccess = {
                        showMessage("Complaint deleted successfully")
                    },
                    onError = { error ->
                        showMessage("Failed to delete complaint: ${error.message}")
                    }
                )
            }
        },
        onUpdateComplaint = { updatedComplaint: Complaint ->
            coroutine.launch {
                adminComplaintViewModel.updateComplaint(
                    complaint = updatedComplaint,
                    onSuccess = {
                        showMessage("Complaint updated successfully")
                    },
                    onError = { error ->
                        showMessage("Failed to update complaint: ${error.message}")
                    }
                )
            }
        },
        onAddClosureReason = { complaint: Complaint, reason: String ->
            coroutine.launch {
                adminComplaintViewModel.addClosureReason(
                    complaint = complaint,
                    reason = reason,
                    onSuccess = {
                        showMessage("Closure reason added successfully")
                    },
                    onError = { error ->
                        showMessage("Failed to add closure reason: ${error.message}")
                    }
                )
            }
        },
        onShowMessage = showMessage
    )
}