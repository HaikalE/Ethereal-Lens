package eu.sisik.backgroundcam

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class RecordingViewModel : ViewModel() {
    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> get() = _isRecording

    fun setRecording(isRecording: Boolean) {
        _isRecording.value = isRecording
    }
}
