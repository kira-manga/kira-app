package me.manga.yamiapk.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import me.manga.yamiapk.navigation.safePopBackStack
import me.manga.yamiapk.presentation.features.complaint.ui.screens.ComplaintScreen
import me.manga.yamiapk.presentation.features.complaint.viewmodes.ComplaintViewModel


@Composable
fun ComplaintScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    complaintViewModel: ComplaintViewModel = hiltViewModel()

    ){

    val coroutine = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        complaintViewModel.loadForUser()
    }

   val complaints by complaintViewModel.userComplaints.collectAsStateWithLifecycle()
    ComplaintScreen(
        complaintsState = complaints,
        onRetry = {
            complaintViewModel.loadForUser()
        },
        onHelp = {
            // Handle help action - maybe navigate to help screen
            // navController.navigate("help")
        },
        onBackClick = {
            navController.safePopBackStack()
        },

        onReplyComplaint = { complaint, replyComplaint ->
            coroutine.launch {
                complaintViewModel.sendComplaint(replyComplaint)
                complaintViewModel.loadForUser()
            }

        },
        onEditComplaint = { complaint , body->
            coroutine.launch {
                complaintViewModel.updateComplaint(complaint.copy(body = body))
                complaintViewModel.loadForUser()
            }
        },
        onDeleteComplaint = { complaint ->
            // Handle delete action
            coroutine.launch {
                complaintViewModel.deleteComplaint(complaint.id)
                complaintViewModel.loadForUser()
            }
        }
    )

}
