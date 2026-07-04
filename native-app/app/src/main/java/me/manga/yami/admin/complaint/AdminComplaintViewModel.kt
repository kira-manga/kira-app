package me.manga.yamiapk.admin.complaint

// presentation/features/complaint/viewmodes/AdminComplaintViewModel.kt


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
import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintStatus
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintType
import me.manga.yamiapk.presentation.features.complaint.usecase.*
import javax.inject.Inject

@HiltViewModel
class AdminComplaintViewModel @Inject constructor(
    private val userIdProvider: UserIdProvider,
    private val getAllComplaintUseCase: GetAllComplaintUseCase,
    private val updateComplaintUseCase: UpdateComplaintUseCase,
    private val deleteComplaintUseCase: DeleteComplaintUseCase
) : ViewModel() {

    private val _complaints = MutableStateFlow<State<List<Complaint>>>(State.Loading)
    val complaints: StateFlow<State<List<Complaint>>> = _complaints

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadAllComplaints()
    }

    /**
     * Load all complaints for admin management
     */
    fun loadAllComplaints() {
        _complaints.value = State.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("AdminComplaintVM", "Loading all complaints...")
                val complaintList = getAllComplaintUseCase()


                Log.d("AdminComplaintVM", "Loaded ${complaintList.size} complaints")
                _complaints.value = State.Success(complaintList)
            } catch (t: Throwable) {
                Log.e("AdminComplaintVM", "Error loading complaints: ${t.message}", t)
                _complaints.value = State.Error.fromException(t)
            }
        }
    }

    /**
     * Update complaint status with admin privileges
     */
    fun updateComplaintStatus(
        complaint: Complaint,
        newStatus: ComplaintStatus,
        onSuccess: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true
                Log.d("AdminComplaintVM", "Updating complaint ${complaint.id} status to $newStatus")

                val updatedComplaint = complaint.copy(status = newStatus)
                updateComplaintUseCase(updatedComplaint)

                // Update local state
                val currentState = _complaints.value
                if (currentState is State.Success) {
                    val updatedList = currentState.data.map {
                        if (it.id == complaint.id) updatedComplaint else it
                    }
                    _complaints.value = State.Success(updatedList)
                }

                Log.d("AdminComplaintVM", "Successfully updated complaint status")
                onSuccess()

            } catch (t: Throwable) {
                Log.e("AdminComplaintVM", "Error updating complaint status: ${t.message}", t)
                onError(t)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Update entire complaint with admin privileges
     */
    fun updateComplaint(
        complaint: Complaint,
        onSuccess: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true
                Log.d("AdminComplaintVM", "Updating complaint ${complaint.id}")

                updateComplaintUseCase(complaint)

                // Update local state
                val currentState = _complaints.value
                if (currentState is State.Success) {
                    val updatedList = currentState.data.map {
                        if (it.id == complaint.id) complaint else it
                    }
                    _complaints.value = State.Success(updatedList)
                }

                Log.d("AdminComplaintVM", "Successfully updated complaint")
                onSuccess()

            } catch (t: Throwable) {
                Log.e("AdminComplaintVM", "Error updating complaint: ${t.message}", t)
                onError(t)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Add closure reason to a complaint
     */
    fun addClosureReason(
        complaint: Complaint,
        reason: String,
        onSuccess: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true
                Log.d("AdminComplaintVM", "Adding closure reason to complaint ${complaint.id}")

                // Add reason to metadata and potentially change status
                val updatedMetadata = complaint.metadata?.toMutableMap() ?: mutableMapOf()
                updatedMetadata["reason"] = reason
                updatedMetadata["reasonAddedBy"] = userIdProvider.getUserId()
                updatedMetadata["reasonAddedAt"] = System.currentTimeMillis().toString()

                // Automatically set status to CLOSED if not already
                val newStatus = if (complaint.status == ComplaintStatus.OPEN ||
                    complaint.status == ComplaintStatus.IN_PROGRESS) {
                    ComplaintStatus.CLOSED
                } else {
                    complaint.status
                }

                val updatedComplaint = complaint.copy(
                    status = newStatus,
                    metadata = updatedMetadata
                )

                updateComplaintUseCase(updatedComplaint)

                // Update local state
                val currentState = _complaints.value
                if (currentState is State.Success) {
                    val updatedList = currentState.data.map {
                        if (it.id == complaint.id) updatedComplaint else it
                    }
                    _complaints.value = State.Success(updatedList)
                }

                Log.d("AdminComplaintVM", "Successfully added closure reason")
                onSuccess()

            } catch (t: Throwable) {
                Log.e("AdminComplaintVM", "Error adding closure reason: ${t.message}", t)
                onError(t)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Delete complaint with admin privileges
     */
    fun deleteComplaint(
        complaint: Complaint,
        onSuccess: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true
                Log.d("AdminComplaintVM", "Deleting complaint ${complaint.id}")

                deleteComplaintUseCase(complaint.id)

                // Update local state
                val currentState = _complaints.value
                if (currentState is State.Success) {
                    val updatedList = currentState.data.filter { it.id != complaint.id }
                    _complaints.value = State.Success(updatedList)
                }

                Log.d("AdminComplaintVM", "Successfully deleted complaint")
                onSuccess()

            } catch (t: Throwable) {
                Log.e("AdminComplaintVM", "Error deleting complaint: ${t.message}", t)
                onError(t)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Bulk update complaints status
     */
    fun bulkUpdateStatus(
        complaintIds: List<String>,
        newStatus: ComplaintStatus,
        onSuccess: (Int) -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true
                Log.d("AdminComplaintVM", "Bulk updating ${complaintIds.size} complaints to $newStatus")

                val currentState = _complaints.value
                if (currentState is State.Success) {
                    var successCount = 0
                    val updatedComplaints = currentState.data.map { complaint ->
                        if (complaint.id in complaintIds) {
                            try {
                                val updatedComplaint = complaint.copy(status = newStatus)
                                updateComplaintUseCase(updatedComplaint)
                                successCount++
                                updatedComplaint
                            } catch (e: Exception) {
                                Log.e("AdminComplaintVM", "Failed to update complaint ${complaint.id}: ${e.message}")
                                complaint // Keep original if update fails
                            }
                        } else {
                            complaint
                        }
                    }

                    _complaints.value = State.Success(updatedComplaints)
                    Log.d("AdminComplaintVM", "Successfully updated $successCount out of ${complaintIds.size} complaints")
                    onSuccess(successCount)
                }

            } catch (t: Throwable) {
                Log.e("AdminComplaintVM", "Error in bulk update: ${t.message}", t)
                onError(t)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Bulk delete complaints
     */
    fun bulkDeleteComplaints(
        complaintIds: List<String>,
        onSuccess: (Int) -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true
                Log.d("AdminComplaintVM", "Bulk deleting ${complaintIds.size} complaints")

                var successCount = 0
                complaintIds.forEach { complaintId ->
                    try {
                        deleteComplaintUseCase(complaintId)
                        successCount++
                    } catch (e: Exception) {
                        Log.e("AdminComplaintVM", "Failed to delete complaint $complaintId: ${e.message}")
                    }
                }

                // Update local state
                val currentState = _complaints.value
                if (currentState is State.Success) {
                    val updatedList = currentState.data.filterNot { it.id in complaintIds.take(successCount) }
                    _complaints.value = State.Success(updatedList)
                }

                Log.d("AdminComplaintVM", "Successfully deleted $successCount out of ${complaintIds.size} complaints")
                onSuccess(successCount)

            } catch (t: Throwable) {
                Log.e("AdminComplaintVM", "Error in bulk delete: ${t.message}", t)
                onError(t)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Get complaints statistics for admin dashboard
     */
    fun getComplaintsStatistics(): ComplaintsStatistics? {
        val currentState = _complaints.value
        return if (currentState is State.Success) {
            val complaints = currentState.data
            ComplaintsStatistics(
                total = complaints.size,
                byStatus = ComplaintStatus.entries.associateWith { status ->
                    complaints.count { it.status == status }
                },
                byType = complaints.groupBy { it.type }.mapValues { it.value.size },
                recentCount = complaints.count { complaint ->
                    val dayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                    (complaint.createdAt?.time ?: 0) > dayAgo
                },
                avgResponseTime = calculateAverageResponseTime(complaints)
            )
        } else {
            null
        }
    }

    private fun calculateAverageResponseTime(complaints: List<Complaint>): Long {
        val resolvedComplaints = complaints.filter {
            it.status == ComplaintStatus.RESOLVED || it.status == ComplaintStatus.CLOSED
        }

        if (resolvedComplaints.isEmpty()) return 0L

        val totalTime = resolvedComplaints.sumOf { complaint ->
            val createdTime = complaint.createdAt?.time ?: System.currentTimeMillis()
            val resolvedTime = complaint.metadata?.get("resolvedAt")?.toString()?.toLongOrNull()
                ?: System.currentTimeMillis()
            resolvedTime - createdTime
        }

        return totalTime / resolvedComplaints.size
    }
}

/**
 * Data class for admin statistics
 */
data class ComplaintsStatistics(
    val total: Int,
    val byStatus: Map<ComplaintStatus, Int>,
    val byType: Map<ComplaintType, Int>,
    val recentCount: Int,
    val avgResponseTime: Long // in milliseconds
)