package com.minseo41.subfeed.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.minseo41.subfeed.data.SubscriptionRepo
import com.minseo41.subfeed.model.SubscribedChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelEditViewModel @Inject constructor(
    private val subscriptionRepo: SubscriptionRepo,
) : ViewModel() {

    val channels: StateFlow<List<SubscribedChannel>> = subscriptionRepo.observeChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateChannel(channel: SubscribedChannel) {
        viewModelScope.launch { subscriptionRepo.updateChannel(channel) }
    }

    fun deleteChannel(id: String) {
        viewModelScope.launch { subscriptionRepo.deleteChannel(id) }
    }
}
