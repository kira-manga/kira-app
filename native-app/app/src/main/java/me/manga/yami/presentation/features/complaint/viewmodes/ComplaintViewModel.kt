package me.manga.yamiapk.presentation.features.complaint.viewmodes

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.domain.auth.UserIdProvider
import me.manga.yamiapk.domain.device.DeviceInfoProvider
import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintStatus
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintType
import me.manga.yamiapk.presentation.features.complaint.usecase.DeleteComplaintUseCase
import me.manga.yamiapk.presentation.features.complaint.usecase.GetAllComplaintUseCase
import me.manga.yamiapk.presentation.features.complaint.usecase.GetUserComplaintUseCase
import me.manga.yamiapk.presentation.features.complaint.usecase.SendComplaintUseCase
import me.manga.yamiapk.presentation.features.complaint.usecase.UpdateComplaintUseCase
import java.util.Date
import javax.inject.Inject


@HiltViewModel
class ComplaintViewModel @Inject constructor(
    private val userIdProvider: UserIdProvider,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val sendComplaintUseCase: SendComplaintUseCase,
    private val getUserComplaintUseCase: GetUserComplaintUseCase,
    private val getAllComplaintUseCase: GetAllComplaintUseCase,
    private val updateComplaintUseCase: UpdateComplaintUseCase,
    private val deleteComplaintUseCase: DeleteComplaintUseCase

) : ViewModel() {


    // UI state flows wrapping our core State<T>
    private val _allComplaints = MutableStateFlow<State<List<Complaint>>>(State.Loading)
    val allComplaints: StateFlow<State<List<Complaint>>> = _allComplaints

    private val _userComplaints = MutableStateFlow<State<List<Complaint>>>(State.Loading)
    val userComplaints: StateFlow<State<List<Complaint>>> = _userComplaints


    fun submit(
        type: ComplaintType,
        subject: String,
        body: String,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val userId = userIdProvider.getUserId()
// Gather device metadata
        val deviceMeta = deviceInfoProvider.getDeviceMetadata()
        val complaint = Complaint(
            userId = userId,
            type = type,
            subject = subject,
            body = body,
            createdAt = Date(),
            status = ComplaintStatus.OPEN,
            metadata = deviceMeta
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = sendComplaintUseCase(complaint)
                onSuccess(id)
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /**
     * Load all complaints into a StateFlow<State<List<Complaint>>>
     */
    fun loadAll() {
        _allComplaints.value = State.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = getAllComplaintUseCase()
                _allComplaints.value = State.Success(list)
            } catch (t: Throwable) {
                _allComplaints.value = State.Error.fromException(t)
            }
        }
    }

    /**
     * Load complaints for a specific user into a StateFlow<State<List<Complaint>>>
     */
    fun loadForUser() {
        _userComplaints.value = State.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = getUserComplaintUseCase(userIdProvider.getUserId())
                _userComplaints.value = State.Success(list)
            } catch (t: Throwable) {
                _userComplaints.value = State.Error.fromException(t)
            }
        }
    }

    fun updateComplaint(complaint: Complaint) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateComplaintUseCase(complaint)
            } catch (t: Throwable) {
            }
        }
    }

    fun sendComplaint(complaint: Complaint) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sendComplaintUseCase(complaint)
//                onSuccess(id)
            } catch (t: Throwable) {
//                onError(t)

            }
        }
    }


    fun deleteComplaint(
        complaintId: String,
//        onSuccess: () -> Unit = {},
//        onError: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                deleteComplaintUseCase(complaintId)
//                onSuccess()
            } catch (t: Throwable) {
//                onError(t)
            }
        }
    }
}
