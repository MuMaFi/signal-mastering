package app.signal.isolate.work

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the one separation that can be in flight.
 *
 * The work outlives the Activity — a long render must survive a rotation or the user
 * switching away — so it runs on a process-scoped job with a foreground service holding
 * the process up, and the UI only observes [state].
 */
object SeparationController {

    private val scope = CoroutineScope(SupervisorJob())
    private val _state = MutableStateFlow<SeparationState>(SeparationState.Idle)
    val state: StateFlow<SeparationState> = _state.asStateFlow()

    private var job: Job? = null

    var lastRequest: SeparationRequest? = null
        private set

    fun start(context: Context, request: SeparationRequest) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        lastRequest = request
        _state.value = SeparationState.Downloading(request.modelId, 0, 0)

        val intent = Intent(app, SeparationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }

        job = scope.launch {
            val result = try {
                SeparationPipeline(app).run(request) { _state.value = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                SeparationState.Cancelled
            }
            _state.value = result
        }
    }

    suspend fun cancel() {
        job?.cancelAndJoin()
        job = null
        _state.value = SeparationState.Cancelled
    }

    fun reset() {
        if (job?.isActive == true) return
        _state.value = SeparationState.Idle
    }
}
