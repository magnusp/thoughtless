package github.magnusp.thoughtless.domain.repository

import github.magnusp.thoughtless.domain.model.ProposalStatus
import github.magnusp.thoughtless.domain.model.TaskProposal
import kotlinx.coroutines.flow.Flow

interface TaskProposalRepository {
    fun getProposals(): Flow<List<TaskProposal>>
    fun getPendingProposals(): Flow<List<TaskProposal>>
    fun getProposalsByProject(projectId: String?): Flow<List<TaskProposal>>
    fun getProposalById(id: String): Flow<TaskProposal?>
    suspend fun saveProposal(proposal: TaskProposal): TaskProposal
    suspend fun updateProposalStatus(id: String, status: ProposalStatus)
    suspend fun deleteProposal(id: String)
}
